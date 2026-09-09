-- ============================================================
-- Agent 对话网页 建表语句（MySQL 8.x，utf8mb4）
-- 应用启动时自动执行（幂等：CREATE TABLE IF NOT EXISTS）
-- 也可手动执行：mysql -uroot -p agent_chat < schema.sql
-- ============================================================

-- 用户表（登录令牌不再存库：会话在 Redis，TTL 过期 + 滑动续期 + 单端互踢）
CREATE TABLE IF NOT EXISTS app_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    username      VARCHAR(50)  NOT NULL COMMENT '登录用户名',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 密码哈希',
    nickname      VARCHAR(50)           DEFAULT NULL COMMENT '昵称',
    created_at    DATETIME              DEFAULT NULL COMMENT '注册时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='用户表';

-- Skill 元数据表（平台所有技能：内置 + 用户上传）
CREATE TABLE IF NOT EXISTS skill (
    id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    name                VARCHAR(100) NOT NULL COMMENT '技能唯一标识',
    short_description   VARCHAR(200)          DEFAULT NULL COMMENT '短描述',
    detailed_description TEXT                 DEFAULT NULL COMMENT '详细描述',
    system_prompt       TEXT                  DEFAULT NULL COMMENT '系统提示词片段',
    tool_names_json     VARCHAR(500)          DEFAULT '[]' COMMENT '引用的内置 Tool 名称 JSON 数组',
    builtin             TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否内置技能（1 内置不可删）',
    created_by          VARCHAR(100)          DEFAULT NULL COMMENT '上传者用户名（内置为 system）',
    created_at          DATETIME              DEFAULT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_skill_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='Skill 元数据表';

-- 用户-Skill 挂载关系表
CREATE TABLE IF NOT EXISTS user_skill (
    id         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id    VARCHAR(100) NOT NULL COMMENT '挂载用户（用户名）',
    skill_name VARCHAR(100) NOT NULL COMMENT '被挂载的 Skill 名称',
    mounted_at DATETIME              DEFAULT NULL COMMENT '挂载时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_skill (user_id, skill_name),
    KEY idx_user_skill_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='用户-Skill 挂载关系表';

-- 对话消息记录表
CREATE TABLE IF NOT EXISTS chat_message (
    id         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id    VARCHAR(100) NOT NULL COMMENT '所属用户（用户名）',
    role       VARCHAR(20)  NOT NULL COMMENT '角色：user / assistant',
    content    TEXT         NOT NULL COMMENT '消息内容',
    created_at DATETIME              DEFAULT NULL COMMENT '发送时间',
    PRIMARY KEY (id),
    KEY idx_chat_user_time (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='对话消息记录表';

-- RAG 知识文档表（用户上传的文档元数据；文本内容切块后存入 agent 侧向量库）
CREATE TABLE IF NOT EXISTS knowledge_document (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    username    VARCHAR(100) NOT NULL COMMENT '上传用户（用户名）',
    doc_id      VARCHAR(64)  NOT NULL COMMENT '文档唯一标识（UUID，对应向量库 docId）',
    doc_name    VARCHAR(255) NOT NULL COMMENT '原始文件名',
    doc_type    VARCHAR(20)  NOT NULL COMMENT '文件类型（txt/md/text）',
    chunk_count INT          NOT NULL DEFAULT 0 COMMENT '切块（向量片段）数量',
    created_at  DATETIME              DEFAULT NULL COMMENT '上传时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_doc_id (doc_id),
    KEY idx_knowledge_user (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='RAG 知识文档元数据表';

-- Agent 对话记忆摘要表（agent 无状态化：摘要由 agent 侧 LLM 压缩生成，backend 持久化，
-- 每次对话请求随 payload 回传给 agent 重建记忆；agent 重启/多实例不丢上下文）
CREATE TABLE IF NOT EXISTS agent_memory (
    user_id    VARCHAR(100) NOT NULL COMMENT '所属用户（用户名）',
    summary    TEXT                  DEFAULT NULL COMMENT '会话历史摘要纯文本',
    updated_at DATETIME              DEFAULT NULL COMMENT '最近更新时间',
    PRIMARY KEY (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='Agent 对话记忆摘要表';
