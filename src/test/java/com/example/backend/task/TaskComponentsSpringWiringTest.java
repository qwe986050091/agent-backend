package com.example.backend.task;

import com.example.backend.chat.AgentTurnService;
import com.example.backend.chat.ConversationRepository;
import com.example.backend.notification.NotificationController;
import com.example.backend.notification.NotificationService;
import com.example.backend.notification.UserNotificationRepository;
import com.example.backend.ratelimit.RateLimitService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 定时任务/站内通知组件装配回归防线（P2-9）。
 *
 * 以最小 Spring 上下文验证 TaskService/TaskRunner/TaskPoller/NotificationService 能真实装配，
 * 并让 {@code @Scheduled} 的 SpEL（poll-interval 占位符乘法）真正解析——
 * P4-17 曾因装配问题在生产 crash-loop，纯 new 的单测无法暴露此类问题（本项目惯例防线）。
 */
class TaskComponentsSpringWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withPropertyValues("app.task.poll-interval-seconds=30")
            .withUserConfiguration(WiringConfig.class);

    @Test
    void contextWiresTaskAndNotificationBeans() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TaskPoller.class);
            assertThat(context).hasSingleBean(TaskService.class);
            assertThat(context).hasSingleBean(TaskRunner.class);
            assertThat(context).hasSingleBean(NotificationService.class);
            assertThat(context).hasSingleBean(NotificationController.class);
        });
    }

    @Configuration
    @EnableScheduling
    static class WiringConfig {

        /** 模拟 Boot 的占位符解析（@Scheduled 中 ${} 内嵌 SpEL 依赖它） */
        @Bean
        static PropertySourcesPlaceholderConfigurer propertyPlaceholderConfigurer() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        TaskProperties taskProperties() {
            return new TaskProperties();
        }

        @Bean
        ScheduledTaskRepository scheduledTaskRepository() {
            return Mockito.mock(ScheduledTaskRepository.class);
        }

        @Bean
        ScheduledTaskRunRepository scheduledTaskRunRepository() {
            return Mockito.mock(ScheduledTaskRunRepository.class);
        }

        @Bean
        ConversationRepository conversationRepository() {
            return Mockito.mock(ConversationRepository.class);
        }

        @Bean
        AgentTurnService agentTurnService() {
            return Mockito.mock(AgentTurnService.class);
        }

        @Bean
        RateLimitService rateLimitService() {
            return Mockito.mock(RateLimitService.class);
        }

        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return Mockito.mock(StringRedisTemplate.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        UserNotificationRepository userNotificationRepository() {
            return Mockito.mock(UserNotificationRepository.class);
        }

        @Bean
        TaskService taskService(TaskProperties props, ScheduledTaskRepository tasks,
                                ScheduledTaskRunRepository runs, TaskRunner runner,
                                StringRedisTemplate redis, ObjectMapper mapper) {
            return new TaskService(props, tasks, runs, runner, redis, mapper);
        }

        @Bean
        TaskRunner taskRunner(TaskProperties props, ScheduledTaskRepository tasks,
                              ScheduledTaskRunRepository runs, ConversationRepository conversations,
                              AgentTurnService turnService, RateLimitService rateLimitService,
                              NotificationService notificationService, StringRedisTemplate redis,
                              ObjectMapper mapper) {
            return new TaskRunner(props, tasks, runs, conversations, turnService,
                    rateLimitService, notificationService, redis, mapper);
        }

        @Bean
        TaskPoller taskPoller(TaskProperties props, ScheduledTaskRepository tasks,
                              TaskService taskService, TaskRunner runner) {
            return new TaskPoller(props, tasks, taskService, runner);
        }

        @Bean
        NotificationService notificationService(UserNotificationRepository repository) {
            return new NotificationService(repository);
        }

        @Bean
        NotificationController notificationController(NotificationService service) {
            return new NotificationController(service);
        }
    }
}