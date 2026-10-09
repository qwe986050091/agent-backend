package com.example.backend.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ChatAttachmentService} 单测（P1-8）：
 * 落盘路径/键结构、消息维度加载（含 base64 与纯视图两模式）、删除时文件与行同时清理、
 * 读字节 round-trip。用 @TempDir 隔离磁盘，仓储用 Mockito 模拟（save 分配自增 id）。
 */
class ChatAttachmentServiceTest {

    @TempDir
    Path tempDir;

    private ChatImageProperties props;
    private ChatAttachmentRepository repository;
    private ChatAttachmentService service;
    private final AtomicLong idSeq = new AtomicLong();

    @BeforeEach
    void setUp() throws Exception {
        props = new ChatImageProperties();
        props.setDir(tempDir.toString());
        repository = mock(ChatAttachmentRepository.class);
        // 模拟 JPA save：分配 id 并回传
        when(repository.save(any(ChatAttachmentEntity.class))).thenAnswer(inv -> {
            ChatAttachmentEntity e = inv.getArgument(0);
            e.setId(idSeq.incrementAndGet());
            return e;
        });
        service = new ChatAttachmentService(props, repository);
        service.init();
    }

    private ChatImageValidator.ValidImage png(byte sizeFill) {
        byte[] bytes = new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, sizeFill, sizeFill};
        return new ChatImageValidator.ValidImage("image/png", bytes);
    }

    @Test
    void persist_writesFilesUnderUserDirAndReturnsEntities() {
        List<ChatAttachmentEntity> saved = service.persist("alice", 42L,
                List.of(png((byte) 1), png((byte) 2)));

        assertThat(saved).hasSize(2);
        assertThat(saved).allSatisfy(e -> {
            assertThat(e.getMessageId()).isEqualTo(42L);
            assertThat(e.getUserId()).isEqualTo("alice");
            assertThat(e.getMediaType()).isEqualTo("image/png");
            assertThat(e.getStorageKey()).startsWith("alice/").endsWith(".png");
        });
        // 两个 key 不重复且文件真实存在
        assertThat(saved.get(0).getStorageKey()).isNotEqualTo(saved.get(1).getStorageKey());
        assertThat(Files.exists(tempDir.resolve(saved.get(0).getStorageKey()))).isTrue();
        assertThat(Files.exists(tempDir.resolve(saved.get(1).getStorageKey()))).isTrue();
    }

    @Test
    void loadForMessages_withBytes_roundTripsBase64() {
        List<ChatAttachmentEntity> saved = service.persist("bob", 7L, List.of(png((byte) 9)));
        when(repository.findByMessageIdInOrderByIdAsc(List.of(7L))).thenReturn(saved);

        Map<Long, List<ChatAttachmentService.LoadedAttachment>> grouped =
                service.loadForMessages(List.of(7L), true);

        assertThat(grouped).containsOnlyKeys(7L);
        ChatAttachmentService.LoadedAttachment loaded = grouped.get(7L).get(0);
        assertThat(loaded.mediaType()).isEqualTo("image/png");
        assertThat(loaded.base64Data()).isNotBlank();
        assertThat(Base64.getDecoder().decode(loaded.base64Data()))
                .containsExactly(png((byte) 9).bytes());
    }

    @Test
    void loadForMessages_metadataOnly_hasNoBase64() {
        List<ChatAttachmentEntity> saved = service.persist("bob", 8L, List.of(png((byte) 3)));
        when(repository.findByMessageIdInOrderByIdAsc(List.of(8L))).thenReturn(saved);

        Map<Long, List<ChatAttachmentService.LoadedAttachment>> grouped =
                service.loadForMessages(List.of(8L), false);

        assertThat(grouped.get(8L).get(0).base64Data()).isNull();
        assertThat(grouped.get(8L).get(0).id()).isEqualTo(saved.get(0).getId());
    }

    @Test
    void readBytes_returnsExactFileContent() throws Exception {
        ChatAttachmentEntity entity = service.persist("carol", 9L, List.of(png((byte) 5))).get(0);

        byte[] bytes = service.readBytes(entity);

        assertThat(bytes).containsExactly(png((byte) 5).bytes());
    }

    @Test
    void deleteForMessages_removesFilesAndRows() {
        List<ChatAttachmentEntity> saved = service.persist("dave", 10L,
                List.of(png((byte) 1), png((byte) 2)));
        when(repository.findByMessageIdIn(List.of(10L))).thenReturn(saved);

        service.deleteForMessages(List.of(10L));

        assertThat(Files.exists(tempDir.resolve(saved.get(0).getStorageKey()))).isFalse();
        assertThat(Files.exists(tempDir.resolve(saved.get(1).getStorageKey()))).isFalse();
        // deleteAll 被调用（用 thenAnswer 记录会更细，这里验证文件清理 + 不抛即可）
        org.mockito.Mockito.verify(repository).deleteAll(saved);
    }
}
