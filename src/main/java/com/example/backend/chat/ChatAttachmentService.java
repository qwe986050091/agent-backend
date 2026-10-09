package com.example.backend.chat;

import com.example.backend.chat.ChatImageValidator.ValidImage;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 对话图片附件存储服务（P1-8）。
 *
 * 字节落本地磁盘 {@code {dir}/{userId}/{uuid}.{ext}}，元数据落 chat_attachment 表。
 * 单机部署阶段足够；未来多实例化时替换为对象存储实现即可（接口调用面不变）。
 */
@Service
public class ChatAttachmentService {

    private static final Logger log = LoggerFactory.getLogger(ChatAttachmentService.class);

    private final ChatImageProperties props;
    private final ChatAttachmentRepository repository;
    private Path rootDir;

    public ChatAttachmentService(ChatImageProperties props, ChatAttachmentRepository repository) {
        this.props = props;
        this.repository = repository;
    }

    @PostConstruct
    void init() throws IOException {
        rootDir = Paths.get(props.getDir()).toAbsolutePath().normalize();
        Files.createDirectories(rootDir);
        log.info("[ChatAttachment] 图片存储目录: {}", rootDir);
    }

    /** 附件视图（历史接口用，不含字节） */
    public record AttachmentView(Long id, String mediaType) {}

    /** 发给 agent 的图片（mimeType + 原始 base64） */
    public record AgentImage(String mimeType, String base64Data) {}

    /**
     * 持久化一条用户消息的全部图片（先写文件再写元数据行）。
     * 文件写入失败会抛出，调用方此时消息行尚未保存或可整体回滚（@Transactional）。
     */
    public List<ChatAttachmentEntity> persist(String userId, Long messageId, List<ValidImage> images) {
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        List<ChatAttachmentEntity> saved = new ArrayList<>();
        for (ValidImage img : images) {
            String ext = extensionOf(img.mimeType());
            String key = userId + "/" + UUID.randomUUID() + "." + ext;
            Path target = resolveSafe(key);
            try {
                Files.createDirectories(target.getParent());
                Files.write(target, img.bytes());
            } catch (IOException e) {
                // 已写文件 best-effort 清理后抛出（未写元数据行，不留 DB 垃圾）
                deleteQuietly(target);
                throw new IllegalStateException("图片保存失败，请稍后重试", e);
            }
            ChatAttachmentEntity entity = new ChatAttachmentEntity();
            entity.setMessageId(messageId);
            entity.setUserId(userId);
            entity.setMediaType(img.mimeType());
            entity.setSizeBytes(img.bytes().length);
            entity.setStorageKey(key);
            saved.add(repository.save(entity));
        }
        return saved;
    }

    /** 鉴权读取单个附件实体（不存在/非本人均为空） */
    public ChatAttachmentEntity findOwned(Long id, String userId) {
        return repository.findByIdAndUserId(id, userId).orElse(null);
    }

    /** 读取附件字节（取图接口用）；文件丢失抛异常由调用方映射 410/404 */
    public byte[] readBytes(ChatAttachmentEntity entity) throws IOException {
        return Files.readAllBytes(resolveSafe(entity.getStorageKey()));
    }

    /**
     * 批量加载历史消息附件，按 messageId 分组（保持消息内顺序）。
     * @param withBytes true=同时读字节并 base64（发给 agent）；false=仅视图（历史接口）
     */
    public Map<Long, List<LoadedAttachment>> loadForMessages(Collection<Long> messageIds, boolean withBytes) {
        Map<Long, List<LoadedAttachment>> grouped = new LinkedHashMap<>();
        if (messageIds == null || messageIds.isEmpty()) {
            return grouped;
        }
        List<ChatAttachmentEntity> entities = repository.findByMessageIdInOrderByIdAsc(messageIds);
        for (ChatAttachmentEntity e : entities) {
            String base64 = null;
            if (withBytes) {
                try {
                    base64 = Base64.getEncoder().encodeToString(readBytes(e));
                } catch (IOException ex) {
                    // 磁盘文件丢失（清理/迁移残留）：跳过图片但不阻断整轮对话
                    log.warn("[ChatAttachment] 附件 {} 文件丢失，跳过: {}", e.getId(), ex.getMessage());
                    continue;
                }
            }
            grouped.computeIfAbsent(e.getMessageId(), k -> new ArrayList<>())
                    .add(new LoadedAttachment(e.getId(), e.getMediaType(), base64));
        }
        return grouped;
    }

    /** 已加载附件（base64Data 仅 withBytes=true 时有值） */
    public record LoadedAttachment(Long id, String mediaType, String base64Data) {}

    /** 删除一批消息的附件（元数据 + 文件）；会话删除时调用，任何失败只记日志不阻断 */
    public void deleteForMessages(Collection<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return;
        }
        List<ChatAttachmentEntity> entities = repository.findByMessageIdIn(messageIds);
        for (ChatAttachmentEntity e : entities) {
            deleteQuietly(resolveSafe(e.getStorageKey()));
        }
        repository.deleteAll(entities);
    }

    /** 防路径穿越：解析后必须仍在根目录内（key 虽然是服务端生成的 UUID，纵深防御） */
    private Path resolveSafe(String key) {
        Path resolved = rootDir.resolve(key).normalize();
        if (!resolved.startsWith(rootDir)) {
            throw new IllegalArgumentException("非法的附件路径");
        }
        return resolved;
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // 清理失败不影响主流程（孤儿文件后续可人工/定时清理）
        }
    }

    private static String extensionOf(String mimeType) {
        return switch (mimeType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            default -> "bin";
        };
    }
}
