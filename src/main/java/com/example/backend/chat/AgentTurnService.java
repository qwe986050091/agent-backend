package com.example.backend.chat;

import com.example.backend.agent.AgentClient;
import com.example.backend.approval.ToolGrantStore;
import com.example.backend.mq.EventPublisher;
import com.example.backend.mq.event.ConversationIndexEvent;
import com.example.backend.observability.TracePersistenceService;
import com.example.backend.skill.SkillEntity;
import com.example.backend.skill.SkillRepository;
import com.example.backend.skill.UserSkillEntity;
import com.example.backend.skill.UserSkillRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对话回合管线（M1a 从 ChatController 平移抽取，零行为变更）。
 *
 * 负责无状态 payload 组装（会话摘要 + 最近历史 + 已挂载技能定义 + 会话级免确认工具）、
 * 消息/摘要落库、RAG 异步索引、会话活跃时间刷新；并暴露同步回合执行入口
 * {@link #executeTurn} 供定时任务（P2-9）等无 SSE 场景复用——普通聊天两条路径
 * （同步 /api/chat 与流式 /api/chat/stream）也全部改为委托本服务。
 *
 * <b>agent 无状态协议：</b>每次调用前由本服务从 MySQL 组装对话状态随 payload 下发；
 * agent 回合结束回传最新摘要，本服务落库。
 */
@Service
public class AgentTurnService {

    private static final Logger log = LoggerFactory.getLogger(AgentTurnService.class);

    /** 下发给 agent 重建记忆的最近历史消息条数（agent 侧压缩阈值 40 / 保留 20） */
    private static final int HISTORY_LIMIT = 30;

    private final ChatMessageRepository chatMessageRepository;
    private final AgentMemoryRepository agentMemoryRepository;
    private final ConversationRepository conversationRepository;
    private final UserSkillRepository userSkillRepository;
    private final SkillRepository skillRepository;
    private final ChatAttachmentService attachmentService;
    private final ToolGrantStore toolGrantStore;
    private final AgentClient agentClient;
    private final TracePersistenceService tracePersistenceService;
    private final EventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public AgentTurnService(ChatMessageRepository chatMessageRepository,
                            AgentMemoryRepository agentMemoryRepository,
                            ConversationRepository conversationRepository,
                            UserSkillRepository userSkillRepository,
                            SkillRepository skillRepository,
                            ChatAttachmentService attachmentService,
                            ToolGrantStore toolGrantStore,
                            AgentClient agentClient,
                            TracePersistenceService tracePersistenceService,
                            EventPublisher eventPublisher,
                            ObjectMapper objectMapper) {
        this.chatMessageRepository = chatMessageRepository;
        this.agentMemoryRepository = agentMemoryRepository;
        this.conversationRepository = conversationRepository;
        this.userSkillRepository = userSkillRepository;
        this.skillRepository = skillRepository;
        this.attachmentService = attachmentService;
        this.toolGrantStore = toolGrantStore;
        this.agentClient = agentClient;
        this.tracePersistenceService = tracePersistenceService;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    /** 同步回合执行结果：agent 结果 + 落库的用户/助手消息 id + 实际使用的 traceId（定时任务执行记录用） */
    public record TurnResult(AgentClient.AgentChatResult agentResult, Long userMessageId,
                             Long assistantMessageId, String traceId) {}

    /**
     * 同步执行一个完整回合（供定时任务等无 SSE 场景复用）：
     * 组装 payload → 落库用户消息 → 调 agent（同步，含熔断/重试）→ 落库助手消息/审计/摘要/活跃时间。
     *
     * agent 调用失败时异常向上抛出（调用方决定失败语义），此时用户消息已落库
     * （与手动对话一致：消息先落库，失败返回降级响应）。
     *
     * @param extraAllowedTools 调用方额外预授权的工具（定时任务 tools_whitelist），
     *                          与会话级 ToolGrantStore 授权求并集后进 payload
     */
    public TurnResult executeTurn(String userId, Long conversationId, String message,
                                  List<String> extraAllowedTools, String traceId) {
        AgentClient.AgentChatPayload payload =
                buildPayload(userId, conversationId, message, extraAllowedTools);
        ChatMessageEntity userMessage = saveMessage(userId, conversationId, "user", message, traceId);

        // 同步 HTTP 调用（AgentClient 内含熔断/重试；异常由调用方处理）
        AgentClient.AgentChatResult result = agentClient.chat(payload);

        String agentTraceId = result.traceId() != null ? result.traceId() : traceId;
        ChatMessageEntity assistant = saveMessage(userId, conversationId, "assistant",
                result.answer(), agentTraceId);
        tracePersistenceService.persistTurn(agentTraceId, userId, conversationId,
                assistant.getId(), result.trace());
        saveSummary(userId, conversationId, result.summary());
        touchConversation(userId, conversationId);
        return new TurnResult(result, userMessage.getId(), assistant.getId(), agentTraceId);
    }

    // ==================== 无状态 payload 组装 ====================

    /**
     * 从 MySQL 组装 agent 对话请求：会话摘要 + 最近历史（升序）+ 已挂载技能定义。
     * 摘要与历史均按 (userId, conversationId) 隔离，保证多会话互不串上下文。
     *
     * @param extraAllowedTools 额外预授权工具（可为 null）；与 ToolGrantStore 求并集
     */
    public AgentClient.AgentChatPayload buildPayload(String userId, Long conversationId, String message,
                                                     List<String> extraAllowedTools) {
        String summary = agentMemoryRepository.findById(new AgentMemoryId(userId, conversationId))
                .map(AgentMemoryEntity::getSummary)
                .orElse(null);

        List<AgentClient.AgentChatMessage> history = recentHistory(userId, conversationId);
        List<Map<String, Object>> skills = mountedSkillDefs(userId);
        // 会话级「始终允许」免确认工具（Redis；TTL 7 天）∪ 调用方预授权（定时任务 tools_whitelist）
        Set<String> allowed = new LinkedHashSet<>(toolGrantStore.list(userId, conversationId));
        if (extraAllowedTools != null) {
            for (String tool : extraAllowedTools) {
                if (tool != null && !tool.isBlank()) {
                    allowed.add(tool);
                }
            }
        }

        return new AgentClient.AgentChatPayload(userId, conversationId, message, summary,
                history, skills, List.copyOf(allowed));
    }

    /**
     * 最近 {@link #HISTORY_LIMIT} 条会话历史消息（反转为时间升序）。
     * user 消息携带的图片从磁盘读取并 base64 一并下发（多轮看图对话需要原图在上下文中）。
     */
    private List<AgentClient.AgentChatMessage> recentHistory(String userId, Long conversationId) {
        List<ChatMessageEntity> recentDesc =
                chatMessageRepository.findTop30ByUserIdAndConversationIdOrderByIdDesc(userId, conversationId);
        if (recentDesc.isEmpty()) {
            return List.of();
        }
        // 批量加载这批消息的附件（含字节/base64），按 messageId 分组
        List<Long> messageIds = recentDesc.stream().map(ChatMessageEntity::getId).toList();
        Map<Long, List<ChatAttachmentService.LoadedAttachment>> atts =
                attachmentService.loadForMessages(messageIds, true);

        List<ChatMessageEntity> asc = new ArrayList<>(recentDesc);
        Collections.reverse(asc);
        return asc.stream()
                .map(m -> new AgentClient.AgentChatMessage(
                        m.getRole(), m.getContent(), toAgentImages(atts.get(m.getId()))))
                .toList();
    }

    private static List<AgentClient.AgentChatImage> toAgentImages(
            List<ChatAttachmentService.LoadedAttachment> loaded) {
        if (loaded == null || loaded.isEmpty()) {
            return List.of();
        }
        return loaded.stream()
                .filter(a -> a.base64Data() != null)
                .map(a -> new AgentClient.AgentChatImage(a.mediaType(), a.base64Data()))
                .toList();
    }

    /** 当前回合图片落盘后并入发给 agent 的 payload（直接用内存中的字节转 base64，避免回读磁盘） */
    public AgentClient.AgentChatPayload attachCurrentImages(
            AgentClient.AgentChatPayload payload, String userId, Long messageId,
            List<ChatImageValidator.ValidImage> images) {
        if (images == null || images.isEmpty()) {
            return payload;
        }
        attachmentService.persist(userId, messageId, images);
        List<AgentClient.AgentChatImage> agentImages = images.stream()
                .map(i -> new AgentClient.AgentChatImage(i.mimeType(),
                        java.util.Base64.getEncoder().encodeToString(i.bytes())))
                .toList();
        return new AgentClient.AgentChatPayload(payload.userId(), payload.conversationId(),
                payload.message(), payload.summary(), payload.history(), payload.skills(),
                payload.allowedTools(), agentImages);
    }

    /** 该用户已挂载技能的完整定义（与 agent 端 SkillDefinition 字段对齐） */
    private List<Map<String, Object>> mountedSkillDefs(String userId) {
        List<UserSkillEntity> mounts = userSkillRepository.findByUserId(userId);
        List<Map<String, Object>> defs = new ArrayList<>();
        for (UserSkillEntity mount : mounts) {
            SkillEntity skill = skillRepository.findByName(mount.getSkillName()).orElse(null);
            if (skill == null) {
                // 挂载关系指向已删除的技能，跳过
                continue;
            }
            Map<String, Object> def = new HashMap<>();
            def.put("name", skill.getName());
            def.put("shortDescription", skill.getShortDescription());
            def.put("detailedDescription", skill.getDetailedDescription());
            def.put("systemPrompt", skill.getSystemPrompt());
            def.put("toolNames", parseToolNames(skill.getToolNamesJson()));
            // 技能类型随 payload 下发 agent：agent 据此决定按普通工具还是子代理（Agent-as-Tool）装配
            def.put("type", skill.getType());
            defs.add(def);
        }
        return defs;
    }

    @SuppressWarnings("unchecked")
    private List<String> parseToolNames(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    // ==================== 落库 ====================

    /** 保存一条消息（带 traceId），返回落库实体（供关联用量行） */
    public ChatMessageEntity saveMessage(String userId, Long conversationId, String role,
                                         String content, String traceId) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setUserId(userId);
        entity.setConversationId(conversationId);
        entity.setRole(role);
        entity.setContent(content);
        entity.setTraceId(traceId);
        ChatMessageEntity saved = chatMessageRepository.save(entity);

        // 异步写入 RAG 长期记忆（向量索引，跨会话）；纯图片回合文本为空不索引；
        // 失败只记日志，不影响聊天主流程
        if (content != null && !content.isBlank()) {
            indexConversationAsync(userId, role, content);
        }
        return saved;
    }

    /** 回写 agent 回传的最新摘要（按会话；无摘要则跳过）；失败不影响聊天主流程 */
    public void saveSummary(String userId, Long conversationId, String summary) {
        if (summary == null || summary.isBlank()) {
            return;
        }
        try {
            AgentMemoryId id = new AgentMemoryId(userId, conversationId);
            AgentMemoryEntity entity = agentMemoryRepository.findById(id)
                    .orElseGet(() -> new AgentMemoryEntity(userId, conversationId, summary));
            entity.setSummary(summary);
            entity.setUpdatedAt(LocalDateTime.now());
            agentMemoryRepository.save(entity);
        } catch (Exception e) {
            log.warn("[Chat] 记忆摘要落库失败（不影响聊天）: {}", e.getMessage());
        }
    }

    /** 对话后刷新会话的最近活跃时间（默认会话 0 不建表记录，跳过） */
    public void touchConversation(String userId, Long conversationId) {
        if (conversationId == null || conversationId == ConversationController.DEFAULT_CONVERSATION_ID) {
            return;
        }
        conversationRepository.findById(conversationId).ifPresent(conv -> {
            if (conv.getUserId().equals(userId)) {
                conv.setUpdatedAt(LocalDateTime.now());
                conversationRepository.save(conv);
            }
        });
    }

    /**
     * 把一条对话投递到索引队列（P1-E）：由 MQ 消费者异步写入 agent 侧向量库。
     *
     * 与改造前的区别：进程重启不再丢任务、agent 短暂不可用可重试；
     * 投递失败由 EventPublisher 回退本地分发，任何异常都不反噬聊天主链路（FR-4.3）。
     */
    private void indexConversationAsync(String userId, String role, String content) {
        eventPublisher.publish(new ConversationIndexEvent(userId, role, content), userId);
    }
}