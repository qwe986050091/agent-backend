package com.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Agent 对话网页后端启动类
 *
 * 包扫描从 com.example 开始，同时覆盖：
 *   - com.example.agent.*   ：复用的 Agent 核心（LangChain4j、Skill、Tools）
 *   - com.example.backend.* ：网页后端业务（用户认证、Skill 上传、对话记录）
 */
@SpringBootApplication
public class AgentBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentBackendApplication.class, args);
        System.out.println("""

                ==========================================================
                  Agent Backend 启动成功（业务后端，端口 8081，MySQL 持久化）
                  Agent 能力通过 HTTP 调用 agent 工程（默认 http://localhost:8080）
                  健康检查: GET  http://localhost:8081/api/health
                  登录:     POST http://localhost:8081/api/auth/login
                  对话:     POST http://localhost:8081/api/chat
                  Skill:    GET  http://localhost:8081/api/skills
                ==========================================================
                """);
    }
}
