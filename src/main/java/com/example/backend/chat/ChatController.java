package com.example.backend.chat;

import com.example.backend.agent.AgentClient;
import com.example.backend.agent.trace.TraceData;
import com.example.backend.approval.ToolGrantStore;
import com.example.backend.auth.AuthInterceptor;
import com.example.backend.common.TraceMdcFilter;
import com.example.backend.feedback.FeedbackService;
import com.example.backend.feedback.FeedbackView;
import com.example.backend.observability.BackendMetrics;
import com.example.backend.observability.TracePersistenceService;
import com.example.backend.skill.SkillEntity;
import com.example.backend.skill.SkillRepository;
import com.example.backend.skill.UserSkillEntity;
import com.example.backend.skill.UserSkillRepository;
import com.example.backend.user.UserEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 对话接口（需登录）
 *
 *   POST /api/chat          发送消息（同步，HTTP 转发给 agent 工程），对话记录落库
 *   POST /api/chat/stream   流式对话（SSE 逐 token），完成后落库
 *   GET  /api/chat/history  当前用户的历史消息
 *
 * <b>无状态化：</b>agent 不保存跨请求状态。每次调用前由本控制器从 MySQL 组装
 * 对话状态（摘要 agent_memory 表 + 最近 30 条历史 chat_message 表 + 已挂载技能定义
 * skill/user_skill 表）随 payload 下发；agent 回合结束回传最新摘要，本控制器落库。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    /** 下发给 agent 重建记忆的最近历史消息条数（agent 侧压缩阈值 40 / 保留 20） */
    private static final int HISTORY_LIMIT = 30;

    /** SSE 单次对话最长时间（5 分钟），超时主动结束，避免连接/线程被无限占用 */
    private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;

    /**
     * SSE 流式对话 / RAG 异步索引的工作线程池（HTTP 转发为阻塞调用）。
     * 有界：core 8 / max 32 / 队列 200，满载后 AbortPolicy 拒绝（返回 503，快速失败而非无限堆积线程）。
     */
    private final ThreadPoolExecutor streamExecutor = new ThreadPoolExecutor(
            8, 32, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(200),
            r -> {
                Thread t = new Thread(r, "chat-sse-stream-" + THREAD_SEQ.incrementAndGet());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    @PreDestroy
    void shutdownExecutor() {
        streamExecutor.shutdown();
    }

    private final AgentClient agentClient;
    private final ChatMessageRepository chatMessageRepository;
    private final AgentMemoryRepository agentMemoryRepository;
    private final ConversationRepository conversationRepository;
    private final UserSkillRepository userSkillRepository;
    private final SkillRepository skillRepository;
    private final ObjectMapper objectMapper;
    private final TracePersistenceService tracePersistenceService;
    private final BackendMetrics metrics;
    private final ToolGrantStore toolGrantStore;
    private final FeedbackService feedbackService;
    private final ChatImageValidator imageValidator;
    private final ChatAttachmentService attachmentService;

    public ChatController(AgentClient agentClient,
                          ChatMessageRepository chatMessageRepository,
                          AgentMemoryRepository agentMemoryRepository,
                          ConversationRepository conversationRepository,
                          UserSkillRepository userSkillRepository,
                          SkillRepository skillRepository,
                          ObjectMapper objectMapper,
                          TracePersistenceService tracePersistenceService,
                          BackendMetrics metrics,
                          ToolGrantStore toolGrantStore,
                          FeedbackService feedbackService,
                          ChatImageValidator imageValidator,
                          ChatAttachmentService attachmentService) {
        this.agentClient = agentClient;
        this.chatMessageRepository = chatMessageRepository;
        this.agentMemoryRepository = agentMemoryRepository;
        this.conversationRepository = conversationRepository;
        this.userSkillRepository = userSkillRepository;
        this.skillRepository = skillRepository;
        this.objectMapper = objectMapper;
        this.tracePersistenceService = tracePersistenceService;
        this.metrics = metrics;
        this.toolGrantStore = toolGrantStore;
        this.feedbackService = feedbackService;
        this.imageValidator = imageValidator;
        this.attachmentService = attachmentService;
    }

    /** 纯图片回合发给 agent 的兜底文案（DeepSeek 要求 content 块里文本可空，这里给模型明确指令） */
    private static String imageOnlyPrompt(int imageCount) {
        return "（用户发送了 " + imageCount + " 张图片，未附文字说明）请查看图片内容并给出简洁回应。";
    }

    /** 解析请求中的会话 id（缺省 0 = 默认/历史会话） */
    private static Long conversationIdOf(ChatSendRequest body) {
        if (body == null) {
            return ConversationController.DEFAULT_CONVERSATION_ID;
        }
        try {
            String raw = body.conversationId();
            return raw == null || raw.isBlank() ? ConversationController.DEFAULT_CONVERSATION_ID : Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return ConversationController.DEFAULT_CONVERSATION_ID;
        }
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> chat(HttpServletRequest request, @RequestBody(required = false) ChatSendRequest body) {
        UserEntity user = (UserEntity) request.getAttribute(AuthInterceptor.CURRENT_USER_ATTR);
        if (body == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "BAD_REQUEST", "message", "请求体为空"));
        }

        // 图片先校验（magic bytes/大小/数量；非法直接 400/413，不写任何数据）
        List<ChatImageValidator.ValidImage> validImages = imageValidator.validate(body.images());
        String rawText = body.message() == null ? "" : body.message().trim();
        if (rawText.isEmpty() && validImages.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "EMPTY_MESSAGE", "message", "消息内容不能为空"));
        }
        // 纯图片回合：库存原文为空（UI 只显示图片），发给 agent 的文本用兜底指令
        String effectiveText = rawText.isEmpty() ? imageOnlyPrompt(validImages.size()) : rawText;

        String userId = user.getUsername();
        Long conversationId = conversationIdOf(body);
        String traceId = traceIdOf(request);
        long start = System.nanoTime();

        // 1. 先组装状态（必须在保存当前消息之前，否则当前消息会被当成历史重复下发）
        AgentClient.AgentChatPayload payload = buildPayload(userId, conversationId, effectiveText);

        // 2. 保存用户消息（纯图片回合 content 为空串）+ 图片附件落盘/落库，当前图片并入 payload
        ChatMessageEntity userMessage = saveMessage(userId, conversationId, "user", rawText, traceId);
        payload = attachCurrentImages(payload, userId, userMessage.getId(), validImages);

        // 3. HTTP 调用 agent 工程（含重试/熔断；失败或熔断中返回 503 友好降级）
        AgentClient.AgentChatResult result;
        try {
            result = agentClient.chat(payload);
        } catch (Exception e) {
            metrics.recordChat("sync", "unavailable", elapsedMs(start));
            log.warn("[Chat] agent 调用失败，返回降级响应: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("code", "AGENT_UNAVAILABLE",
                            "message", "AI 服务暂时不可用（" + e.getMessage() + "），请稍后重试"));
        }

        // 4. 保存助手回复（带 traceId）+ 审计/用量落库 + 回写最新摘要
        Long assistantMessageId = saveMessage(userId, conversationId, "assistant",
                result.answer(), result.traceId() != null ? result.traceId() : traceId).getId();
        String agentTraceId = result.traceId() != null ? result.traceId() : traceId;
        tracePersistenceService.persistTurn(agentTraceId, userId, conversationId,
                assistantMessageId, result.trace());
        saveSummary(userId, conversationId, result.summary());
        touchConversation(userId, conversationId);
        metrics.recordChat("sync", "success", elapsedMs(start));

        Map<String, Object> resp = new HashMap<>();
        resp.put("answer", result.answer());
        resp.put("traceId", agentTraceId);
        if (result.trace() != null && result.trace().usage() != null) {
            resp.put("usage", result.trace().usage());
        }
        return ResponseEntity.ok(resp);
    }

    /**
     * 流式对话（SSE）：逐 token 转发给前端，流结束后把完整回复与最新摘要落库
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(HttpServletRequest request, @RequestBody(required = false) ChatSendRequest body) {
        UserEntity user = (UserEntity) request.getAttribute(AuthInterceptor.CURRENT_USER_ATTR);
        if (body == null) {
            throw new IllegalArgumentException("请求体为空");
        }

        // 以下校验均在创建 emitter 之前抛异常 → 走全局异常处理返回 400/413 JSON，前端按普通 HTTP 错误处理
        List<ChatImageValidator.ValidImage> validImages = imageValidator.validate(body.images());
        String rawTextStream = body.message() == null ? "" : body.message().trim();
        if (rawTextStream.isEmpty() && validImages.isEmpty()) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
        String effectiveText = rawTextStream.isEmpty()
                ? imageOnlyPrompt(validImages.size()) : rawTextStream;

        // 回合开始即计时；outcome 指标全路径只记一次（超时/断开/成功/失败回调可能重入）
        long streamStart = System.nanoTime();
        AtomicBoolean outcomeRecorded = new AtomicBoolean(false);
        // 客户端主动断开/超时标记。时序依据：容器探测到前端断连会先回调 onError/onTimeout（置位），
        // 之后工作线程读流才失败进入 terminal 回调；而服务端自身错误是「先 terminal 回调、
        // 后 completeWithError 才触发 onError」，故 terminal 时刻该标记能区分两种来源。
        AtomicBoolean clientInitiated = new AtomicBoolean(false);

        // 5 分钟超时：超长回复/僵死连接不会无限占用线程与连接
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        // agent 响应体流引用（取消钩子）：前端断开/超时时 close 该流，主动终止对 agent 的请求
        AtomicReference<InputStream> agentBodyRef = new AtomicReference<>();
        emitter.onTimeout(() -> {
            log.warn("[Chat] SSE 超过 {}ms 未完成，主动断开并取消 agent 请求", SSE_TIMEOUT_MS);
            clientInitiated.set(true);
            recordOutcomeOnce(metrics, outcomeRecorded, "cancelled", streamStart);
            closeAgentStream(agentBodyRef);
            emitter.completeWithError(new TimeoutException("响应超时，请重试"));
        });
        emitter.onError(e -> {
            // 前端断开/发送失败（或服务端 completeWithError 的二次回调，此时 terminal 已处理）
            clientInitiated.set(true);
            log.info("[Chat] 前端连接断开，取消 agent 流式请求");
            closeAgentStream(agentBodyRef);
        });
        emitter.onCompletion(() -> {
            // 流正常结束（agentBodyRef 已由 AgentClient 关闭）；兜底再关一次，确保无残留
            closeAgentStream(agentBodyRef);
        });

        String userId = user.getUsername();
        Long conversationId = conversationIdOf(body);
        String traceId = traceIdOf(request);

        // 1. 先组装状态（在保存当前消息之前；纯图片回合发给 agent 的是兜底指令文本）
        AgentClient.AgentChatPayload payloadBase = buildPayload(userId, conversationId, effectiveText);

        // 2. 用户消息先落库（带 traceId；纯图片回合 content 为空串）+ 附件落盘并把当前图片并入 payload
        ChatMessageEntity userMessage = saveMessage(userId, conversationId, "user", rawTextStream, traceId);
        final AgentClient.AgentChatPayload payload =
                attachCurrentImages(payloadBase, userId, userMessage.getId(), validImages);

        try {
            metrics.changeSseActive(+1);
            // wrapWithMdc：把入站 traceId 交接给工作线程，worker 日志与调 agent 的头都能带上
            streamExecutor.execute(TraceMdcFilter.wrapWithMdc(() -> {
                try {
                    agentClient.chatStream(
                            payload, emitter,
                            // 流正常结束：助手消息 + trace 审计/用量 + 最新摘要落库
                            (fullAnswer, summary, traceData) -> {
                                ChatMessageEntity assistant =
                                        saveMessage(userId, conversationId, "assistant", fullAnswer, traceId);
                                tracePersistenceService.persistTurn(traceId, userId, conversationId,
                                        assistant.getId(), traceData);
                                saveSummary(userId, conversationId, summary);
                                touchConversation(userId, conversationId);
                                recordOutcomeOnce(metrics, outcomeRecorded, "success", streamStart);
                            },
                            // 终态错误：仅「前端断连/超时」按取消记账（带已累积 trace）；
                            // 熔断/5xx/agent 故障只打指标，不写用量行（避免污染用量汇总）
                            (err, accumulated) -> {
                                String outcome = terminalOutcome(err, clientInitiated.get());
                                recordOutcomeOnce(metrics, outcomeRecorded, outcome, streamStart);
                                if ("cancelled".equals(outcome)) {
                                    tracePersistenceService.persistCancelledTurn(
                                            traceId, userId, conversationId, null, accumulated);
                                }
                            },
                            agentBodyRef);
                } finally {
                    metrics.changeSseActive(-1);
                }
            }));
        } catch (RejectedExecutionException e) {
            // 线程池/队列已满：快速失败，不排队堆积
            metrics.changeSseActive(-1);
            recordOutcomeOnce(metrics, outcomeRecorded, "rejected", streamStart);
            log.warn("[Chat] 流式线程池已满，拒绝请求");
            emitter.completeWithError(new IllegalStateException("当前对话繁忙，请稍后重试"));
        }

        return emitter;
    }

    /** outcome 指标只记一次 */
    private static void recordOutcomeOnce(BackendMetrics metrics, AtomicBoolean flag,
                                          String outcome, long startNanos) {
        if (flag.compareAndSet(false, true)) {
            metrics.recordChat("stream", outcome, (System.nanoTime() - startNanos) / 1_000_000L);
        }
    }

    /**
     * 终态异常分类：客户端主动断开/超时=cancelled，熔断=unavailable，其余（agent 故障/网络）=error。
     * clientInitiated 由 emitter onError/onTimeout 先于 terminal 回调置位（含静默思考期停止）。
     */
    private static String terminalOutcome(Throwable err, boolean clientInitiated) {
        if (clientInitiated || err instanceof AgentClient.StreamClientClosedException) {
            return "cancelled";
        }
        if (err instanceof io.github.resilience4j.circuitbreaker.CallNotPermittedException) {
            return "unavailable";
        }
        return "error";
    }

    /** 关闭正在进行的 agent 流式请求响应流（前端断开/超时取消钩子），未开始或已结束则空操作 */
    private static void closeAgentStream(AtomicReference<InputStream> bodyRef) {
        InputStream in = bodyRef.get();
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
                // 已断开则忽略
            }
        }
    }

    @GetMapping("/history")
    public ResponseEntity<?> history(HttpServletRequest request, @RequestParam(defaultValue = "0") Long conversationId) {
        UserEntity user = (UserEntity) request.getAttribute(AuthInterceptor.CURRENT_USER_ATTR);
        List<ChatMessageEntity> messages =
                chatMessageRepository.findByUserIdAndConversationIdOrderByCreatedAtAsc(
                        user.getUsername(), conversationId);

        // 批量水合赞/踩状态（单次 IN 查询），历史气泡据此回显已选评价
        List<String> traceIds = messages.stream()
                .map(ChatMessageEntity::getTraceId)
                .filter(t -> t != null && !t.isBlank())
                .toList();
        Map<String, FeedbackView> feedbackMap =
                feedbackService.viewByTraceIds(user.getUsername(), traceIds);

        // 批量水合图片附件（P1-8，仅元数据；字节走 GET /api/chat/attachments/{id} 鉴权拉取）
        Map<Long, List<ChatAttachmentService.LoadedAttachment>> attachmentMap =
                attachmentService.loadForMessages(
                        messages.stream().map(ChatMessageEntity::getId).toList(), false);

        List<Map<String, Object>> list = messages.stream().map(m -> {
            Map<String, Object> item = new HashMap<>();
            item.put("role", m.getRole());
            item.put("content", m.getContent());
            item.put("createdAt", m.getCreatedAt());
            // traceId 仅助手消息用于历史 trace 懒加载（用户消息/历史数据可能为 null）
            item.put("traceId", m.getTraceId());
            // 本回合反馈（赞/踩 + 原因 + 备注）；无反馈为 null
            item.put("feedback", m.getTraceId() == null ? null : feedbackMap.get(m.getTraceId()));
            // 本消息图片附件（仅 user 消息可能有），无则空数组
            List<Map<String, Object>> atts = attachmentMap
                    .getOrDefault(m.getId(), List.of()).stream()
                    .map(a -> {
                        Map<String, Object> view = new HashMap<>();
                        view.put("id", a.id());
                        // 字段名与发送入参/agent 协议统一为 mimeType（勿写 mediaType，前端绑不上）
                        view.put("mimeType", a.mediaType());
                        return view;
                    }).toList();
            item.put("attachments", atts);
            return item;
        }).toList();

        return ResponseEntity.ok(Map.of("messages", list));
    }

    /**
     * 鉴权读取图片附件字节（P1-8）。
     * <img> 无法带 Authorization 头，前端以 fetch+Blob 方式调用；接口本身仍在登录拦截范围内，
     * 且二次校验附件归属（只能取本人图片）。
     */
    @GetMapping("/attachments/{id}")
    public ResponseEntity<byte[]> getAttachment(HttpServletRequest request,
                                                @org.springframework.web.bind.annotation.PathVariable Long id) {
        String userId = ((UserEntity) request.getAttribute(AuthInterceptor.CURRENT_USER_ATTR)).getUsername();
        ChatAttachmentEntity entity = attachmentService.findOwned(id, userId);
        if (entity == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] bytes = attachmentService.readBytes(entity);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(entity.getMediaType()))
                    // 私人图片：允许前端/CDN 短时缓存，禁止中间人之外的共享缓存
                    .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                    .body(bytes);
        } catch (java.io.IOException e) {
            log.warn("[Chat] 附件 {} 读取失败: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.GONE).build();
        }
    }

    // ==================== 无状态 payload 组装 ====================

    /**
     * 从 MySQL 组装 agent 对话请求：会话摘要 + 最近历史（升序）+ 已挂载技能定义。
     * 摘要与历史均按 (userId, conversationId) 隔离，保证多会话互不串上下文。
     */
    private AgentClient.AgentChatPayload buildPayload(String userId, Long conversationId, String message) {
        String summary = agentMemoryRepository.findById(new AgentMemoryId(userId, conversationId))
                .map(AgentMemoryEntity::getSummary)
                .orElse(null);

        List<AgentClient.AgentChatMessage> history = recentHistory(userId, conversationId);
        List<Map<String, Object>> skills = mountedSkillDefs(userId);
        // 会话级「始终允许」免确认工具（Redis；TTL 7 天）
        List<String> allowedTools = List.copyOf(toolGrantStore.list(userId, conversationId));

        return new AgentClient.AgentChatPayload(userId, conversationId, message, summary,
                history, skills, allowedTools);
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
    private AgentClient.AgentChatPayload attachCurrentImages(
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
    private ChatMessageEntity saveMessage(String userId, Long conversationId, String role,
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

    /** 从 TraceMdcFilter 设置的请求属性取 traceId（理论上必有，兜底 null） */
    private static String traceIdOf(HttpServletRequest request) {
        Object traceId = request.getAttribute(TraceMdcFilter.TRACE_REQUEST_ATTR);
        return traceId == null ? null : traceId.toString();
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** 回写 agent 回传的最新摘要（按会话；无摘要则跳过）；失败不影响聊天主流程 */
    private void saveSummary(String userId, Long conversationId, String summary) {
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
    private void touchConversation(String userId, Long conversationId) {
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
     * 异步把一条对话索引到 agent 侧向量库（RAG 长期记忆），异常不抛出；traceId 随 MDC 交接。
     * 线程池满拒绝也必须吞掉：best-effort 索引不能反噬对话主链路（FR-4.3）。
     */
    private void indexConversationAsync(String userId, String role, String content) {
        try {
            streamExecutor.execute(TraceMdcFilter.wrapWithMdc(() -> {
                try {
                    agentClient.indexConversation(userId, role, content);
                } catch (Exception e) {
                    log.warn("[Chat] 对话 RAG 索引失败（不影响聊天）: {}", e.getMessage());
                }
            }));
        } catch (RejectedExecutionException e) {
            log.warn("[Chat] RAG 索引线程池已满，跳过本条长期记忆索引（不影响聊天）");
        }
    }
}
