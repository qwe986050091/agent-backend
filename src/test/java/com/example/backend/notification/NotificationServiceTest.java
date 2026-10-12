package com.example.backend.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link NotificationService} 单元测试：任务结果通知写入（摘要截断/折叠空白/best-effort）、
 * 游标拉取收敛、未读数、标记已读归属校验。
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock private UserNotificationRepository repository;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(repository);
    }

    @Test
    void notifyTaskResult_savesWithSummarizedBody() {
        service.notifyTaskResult("alice", "早报", "第一行\n第二行   有   空白", 5L);

        ArgumentCaptor<UserNotificationEntity> saved = ArgumentCaptor.forClass(UserNotificationEntity.class);
        verify(repository).save(saved.capture());
        UserNotificationEntity n = saved.getValue();
        assertThat(n.getUserId()).isEqualTo("alice");
        assertThat(n.getType()).isEqualTo(UserNotificationEntity.TYPE_TASK_RESULT);
        assertThat(n.getTitle()).contains("早报");
        assertThat(n.getBody()).isEqualTo("第一行 第二行 有 空白");
        assertThat(n.getTargetConversationId()).isEqualTo(5L);
        assertThat(n.getReadAt()).isNull();
    }

    @Test
    void notifyTaskResult_truncatesLongAnswerTo120() {
        service.notifyTaskResult("alice", "早报", "x".repeat(500), 5L);

        ArgumentCaptor<UserNotificationEntity> saved = ArgumentCaptor.forClass(UserNotificationEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getBody()).hasSize(120);
    }

    @Test
    void notifyTaskResult_blankAnswer_usesFallbackText() {
        service.notifyTaskResult("alice", "早报", "   ", 5L);

        ArgumentCaptor<UserNotificationEntity> saved = ArgumentCaptor.forClass(UserNotificationEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getBody()).contains("点击查看结果");
    }

    @Test
    void notifyTaskResult_repositoryFailure_isSwallowed() {
        when(repository.save(any(UserNotificationEntity.class)))
                .thenThrow(new RuntimeException("db down"));

        // best-effort：不得把异常抛回任务执行主链路
        service.notifyTaskResult("alice", "早报", "回答", 5L);
    }

    @Test
    void list_clampsLimitAndAfterId() {
        when(repository.findByUserIdAndIdGreaterThanOrderByIdAsc(eq("alice"), eq(0L), any(Pageable.class)))
                .thenReturn(List.of());

        service.list("alice", -5L, 999);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByUserIdAndIdGreaterThanOrderByIdAsc(eq("alice"), eq(0L), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(50);
    }

    @Test
    void unreadCount_delegates() {
        when(repository.countByUserIdAndReadAtIsNull("alice")).thenReturn(3L);
        assertThat(service.unreadCount("alice")).isEqualTo(3L);
    }

    @Test
    void markRead_marksUnreadNotification() {
        UserNotificationEntity n = new UserNotificationEntity();
        n.setId(8L);
        n.setUserId("alice");
        when(repository.findByIdAndUserId(8L, "alice")).thenReturn(Optional.of(n));

        assertThat(service.markRead("alice", 8L)).isTrue();

        assertThat(n.getReadAt()).isNotNull();
        verify(repository).save(n);
    }

    @Test
    void markRead_alreadyRead_isIdempotentWithoutSave() {
        UserNotificationEntity n = new UserNotificationEntity();
        n.setId(8L);
        n.setUserId("alice");
        n.setReadAt(LocalDateTime.now().minusMinutes(1));
        when(repository.findByIdAndUserId(8L, "alice")).thenReturn(Optional.of(n));

        assertThat(service.markRead("alice", 8L)).isTrue();

        verify(repository, never()).save(any());
    }

    @Test
    void markRead_notOwned_returnsFalse() {
        when(repository.findByIdAndUserId(anyLong(), anyString())).thenReturn(Optional.empty());
        assertThat(service.markRead("alice", 99L)).isFalse();
        verify(repository, never()).save(any());
    }
}