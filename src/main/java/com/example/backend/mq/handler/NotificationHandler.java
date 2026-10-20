package com.example.backend.mq.handler;

import com.example.backend.mq.EventHandler;
import com.example.backend.mq.EventTopics;
import com.example.backend.mq.event.NotificationCreatedEvent;
import com.example.backend.notification.NotificationService;
import com.example.backend.notification.NotificationStreamService;
import org.springframework.stereotype.Component;

/**
 * 通知消费者：落库 + 通过 SSE 实时推给在线端（替代前端 30s 轮询）。
 *
 * 落库失败会抛出 → MQ 重投；离线用户靠游标接口补齐，不会漏。
 */
@Component
public class NotificationHandler implements EventHandler<NotificationCreatedEvent> {

    private final NotificationService notificationService;
    private final NotificationStreamService streamService;

    public NotificationHandler(NotificationService notificationService,
                               NotificationStreamService streamService) {
        this.notificationService = notificationService;
        this.streamService = streamService;
    }

    @Override
    public Class<NotificationCreatedEvent> type() {
        return NotificationCreatedEvent.class;
    }

    @Override
    public String topic() {
        return EventTopics.NOTIFICATION;
    }

    @Override
    public void handle(NotificationCreatedEvent event) {
        if (event == null || event.userId() == null) {
            return;
        }
        var saved = notificationService.save(event);
        if (saved != null) {
            streamService.push(event.userId(), saved);
        }
    }
}
