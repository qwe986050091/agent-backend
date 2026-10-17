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

-- 对话会话表（多会话：每个用户可建多个独立会话，互不串上下文）
-- 注：chat_message.conversation_id = 0 表示"默认/历史会话"（多会话功能上线前的遗留数据）
CREATE TABLE IF NOT EXISTS conversation (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id    VARCHAR(100) NOT NULL COMMENT '所属用户（用户名）',
    title      VARCHAR(200) NOT NULL COMMENT '会话标题',
    created_at DATETIME              DEFAULT NULL COMMENT '创建时间',
    updated_at DATETIME              DEFAULT NULL COMMENT '最近活跃时间（列表按此倒序）',
    PRIMARY KEY (id),
    KEY idx_conversation_user (user_id, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='对话会话表';

-- 对话消息记录表（conversation_id 区分会话；0=默认/历史会话）
CREATE TABLE IF NOT EXISTS chat_message (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id         VARCHAR(100) NOT NULL COMMENT '所属用户（用户名）',
    conversation_id BIGINT       NOT NULL DEFAULT 0 COMMENT '所属会话（0=默认/历史会话）',
    role            VARCHAR(20)  NOT NULL COMMENT '角色：user / assistant',
    content         TEXT         NOT NULL COMMENT '消息内容',
    created_at      DATETIME              DEFAULT NULL COMMENT '发送时间',
    PRIMARY KEY (id),
    KEY idx_chat_user_conv (user_id, conversation_id, created_at)
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
-- 主键 (user_id, conversation_id)：摘要按会话隔离（0=默认/历史会话）
CREATE TABLE IF NOT EXISTS agent_memory (
    user_id         VARCHAR(100) NOT NULL COMMENT '所属用户（用户名）',
    conversation_id BIGINT       NOT NULL DEFAULT 0 COMMENT '所属会话（0=默认/历史会话）',
    summary         TEXT                  DEFAULT NULL COMMENT '会话历史摘要纯文本',
    updated_at      DATETIME              DEFAULT NULL COMMENT '最近更新时间',
    PRIMARY KEY (user_id, conversation_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='Agent 对话记忆摘要表';

-- ============================================================
-- P1-8 多模态：对话图片附件元数据（字节落磁盘 {CHAT_IMAGE_DIR}/{user}/{uuid}.ext）
-- 一条 user 消息可挂多张；仅 user 消息有附件。无外键约束（与 chat_message 删除同事务清理）。
-- ============================================================
CREATE TABLE IF NOT EXISTS chat_attachment (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    message_id  BIGINT       NOT NULL COMMENT '所属用户消息 id（chat_message.id）',
    user_id     VARCHAR(100) NOT NULL COMMENT '所属用户（用户名，鉴权读取）',
    media_type  VARCHAR(50)  NOT NULL COMMENT '图片 MIME：image/jpeg|png|gif|webp',
    file_name   VARCHAR(255)          DEFAULT NULL COMMENT '原始文件名（可选）',
    size_bytes  BIGINT       NOT NULL DEFAULT 0 COMMENT '原始字节数',
    storage_key VARCHAR(255) NOT NULL COMMENT '磁盘相对路径 key：{userId}/{uuid}.ext',
    created_at  DATETIME              DEFAULT NULL COMMENT '上传时间',
    PRIMARY KEY (id),
    KEY idx_attachment_message (message_id),
    KEY idx_attachment_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='对话图片附件元数据表';

-- ============================================================
-- P2-10 全链路可观测：token 用量 / 工具调用 / RAG 召回 审计表
-- 注意：chat_message.trace_id 列无法用 IF NOT EXISTS 自动加，
--       需手工执行（见文件末尾 ALTER 语句，沿用 P1-8 做法）。
-- ============================================================

-- 每轮对话一行的 LLM token 用量与费用（按用户/会话/模型可聚合出账单）
CREATE TABLE IF NOT EXISTS llm_usage_record (
    id                   BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    trace_id             VARCHAR(64)   NOT NULL COMMENT '全链路 traceId',
    user_id              VARCHAR(100)  NOT NULL COMMENT '所属用户（用户名）',
    conversation_id      BIGINT        NOT NULL DEFAULT 0 COMMENT '所属会话（0=默认会话）',
    assistant_message_id BIGINT                 DEFAULT NULL COMMENT '关联的助手消息 id（chat_message.id）',
    model                VARCHAR(100)           DEFAULT NULL COMMENT '实际服务模型名',
    input_tokens         INT           NOT NULL DEFAULT 0 COMMENT '输入 token 合计',
    output_tokens        INT           NOT NULL DEFAULT 0 COMMENT '输出 token 合计',
    total_tokens         INT           NOT NULL DEFAULT 0 COMMENT '总 token 合计',
    cost_yuan            DECIMAL(12,6) NOT NULL DEFAULT 0 COMMENT '估算费用（元，按 billing 配置单价）',
    rounds               INT           NOT NULL DEFAULT 0 COMMENT 'LLM 调用轮次数',
    fallback             TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '是否走了备用模型',
    cancelled            TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '是否被中途取消',
    duration_ms          BIGINT        NOT NULL DEFAULT 0 COMMENT 'agent 侧整轮耗时 ms',
    created_at           DATETIME               DEFAULT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_usage_trace (trace_id),
    KEY idx_usage_user_created (user_id, created_at),
    KEY idx_usage_conv (conversation_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='LLM token 用量与费用记录表';

-- 每次工具调用一行（参数/结果预览/成败/耗时）
CREATE TABLE IF NOT EXISTS tool_call_record (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    trace_id        VARCHAR(64)  NOT NULL COMMENT '全链路 traceId',
    user_id         VARCHAR(100) NOT NULL COMMENT '所属用户（用户名）',
    conversation_id BIGINT       NOT NULL DEFAULT 0 COMMENT '所属会话',
    round_seq       INT          NOT NULL DEFAULT 0 COMMENT '发生在第几个 LLM 轮次',
    tool_name       VARCHAR(100) NOT NULL COMMENT '工具方法名',
    agent_id        VARCHAR(100)          DEFAULT NULL COMMENT '发起该工具调用的 Agent：NULL=主 Agent，非空=子代理技能名（Agent-as-Tool 归属）',
    arguments       TEXT                  DEFAULT NULL COMMENT '参数 JSON（agent 侧截断 500）',
    result_preview  VARCHAR(1000)         DEFAULT NULL COMMENT '结果文本预览（成功；截断 300）',
    success         TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否成功',
    error_message   VARCHAR(500)          DEFAULT NULL COMMENT '失败错误摘要',
    duration_ms     BIGINT       NOT NULL DEFAULT 0 COMMENT '近似执行耗时 ms',
    created_at      DATETIME              DEFAULT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_tool_trace (trace_id),
    KEY idx_tool_user_created (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='工具调用审计记录表';

-- 每次 RAG 检索的每个召回片段一行（无召回也由 llm_usage_record 侧不体现；miss 仅在指标计数）
CREATE TABLE IF NOT EXISTS rag_recall_record (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    trace_id        VARCHAR(64)  NOT NULL COMMENT '全链路 traceId',
    user_id         VARCHAR(100) NOT NULL COMMENT '所属用户（用户名）',
    conversation_id BIGINT       NOT NULL DEFAULT 0 COMMENT '所属会话',
    query_text      VARCHAR(1000)         DEFAULT NULL COMMENT '触发检索的问题（截断 150）',
    doc_id          VARCHAR(64)           DEFAULT NULL COMMENT '文档 id（历史对话片段可能为空）',
    source_name     VARCHAR(255)          DEFAULT NULL COMMENT '来源名（文件名/历史对话）',
    source_type     VARCHAR(20)           DEFAULT NULL COMMENT '来源类型 document/conversation',
    segment_text    TEXT                  DEFAULT NULL COMMENT '召回片段文本（截断 100）',
    score           DOUBLE       NOT NULL DEFAULT 0 COMMENT '向量相似度分数',
    channel         VARCHAR(10)           DEFAULT NULL COMMENT '命中通道 vector/keyword/both（P1-9.3）',
    rerank_score    DOUBLE                DEFAULT NULL COMMENT 'Rerank 精排 raw logit（P1-9.3）',
    seq             INT          NOT NULL DEFAULT 0 COMMENT '片段在本次检索中的序号',
    created_at      DATETIME              DEFAULT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_rag_trace (trace_id),
    KEY idx_rag_user_created (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='RAG 召回审计记录表';

-- ============================================================
-- P2-11.1 回答反馈（点赞/点踩 + bad case）
-- 反馈以「回合」为粒度：一个用户对一个 traceId 至多一行（再点同一评价=取消）。
-- trace_id 同时出现在同回合的 user/assistant 两条 chat_message 上，归属校验沿用
-- existsByTraceIdAndUserId。新表随启动自动创建，无需手工 ALTER。
-- ============================================================
CREATE TABLE IF NOT EXISTS message_feedback (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    trace_id        VARCHAR(64)  NOT NULL COMMENT '被反馈回合的 traceId（=chat_message.trace_id）',
    user_id         VARCHAR(100) NOT NULL COMMENT '反馈用户（用户名）',
    conversation_id BIGINT       NOT NULL DEFAULT 0 COMMENT '所属会话（冗余自消息行，便于聚合）',
    rating          TINYINT      NOT NULL COMMENT '评价：1=赞，-1=踩',
    reason_code     VARCHAR(50)           DEFAULT NULL COMMENT '点踩原因码（白名单：factual_error/off_topic/incomplete/outdated/tool_error/bad_format/other）',
    comment         VARCHAR(1000)         DEFAULT NULL COMMENT '用户补充说明（截断 1000）',
    created_at      DATETIME              DEFAULT NULL COMMENT '首次提交时间',
    updated_at      DATETIME              DEFAULT NULL COMMENT '最近修改时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_feedback_trace_user (trace_id, user_id),
    KEY idx_feedback_user_created (user_id, created_at),
    KEY idx_feedback_rating_created (rating, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='回答反馈（赞/踩）记录表';

-- ============================================================
-- P4-17 内容安全：prompt 注入 / 敏感信息检测记录表（新表，启动即建，无需手工 ALTER）
-- 一行 = 一次检测事件（一回合可多行：input/rag/tool_output/output 各通道）；
-- monitor 期 action 恒为 none，分析"本该拦截"看 score >= 8 的行。
-- ============================================================
CREATE TABLE IF NOT EXISTS prompt_safety_record (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    trace_id        VARCHAR(64)  NOT NULL COMMENT '回合 traceId（=chat_message.trace_id）',
    user_id         VARCHAR(100) NOT NULL DEFAULT '' COMMENT '用户（用户名）',
    conversation_id BIGINT       NOT NULL DEFAULT 0 COMMENT '所属会话（冗余自消息行，便于聚合）',
    channel         VARCHAR(20)  NOT NULL COMMENT '检测通道：input/rag/tool_output/output/outbound_http',
    rule_hits       JSON         DEFAULT NULL COMMENT '命中规则明细 [{ruleId,category,weight}]',
    score           INT          NOT NULL DEFAULT 0 COMMENT '规则加权分',
    verdict         VARCHAR(20)  NOT NULL DEFAULT 'benign' COMMENT '判定：benign/suspicious/malicious',
    detector        VARCHAR(20)  NOT NULL DEFAULT 'rule' COMMENT '判定来源：rule/llm/dlp',
    action          VARCHAR(20)  NOT NULL DEFAULT 'none' COMMENT '实际动作：none/warn/block',
    llm_reason      VARCHAR(500) DEFAULT NULL COMMENT 'LLM 分类器理由（detector=llm 时）',
    excerpt         VARCHAR(500) DEFAULT NULL COMMENT '命中片段（截断，复盘用；不存全量原文）',
    latency_ms      INT          DEFAULT NULL COMMENT '检测耗时',
    created_at      DATETIME     DEFAULT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_safety_trace (trace_id),
    KEY idx_safety_user_created (user_id, created_at),
    KEY idx_safety_verdict_created (verdict, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='prompt 注入/内容安全检测记录（P4-17）';

-- ============================================================
-- P2-9 定时/主动 Agent：任务定义 / 执行记录 / 站内通知（新表，启动即建，无需手工 ALTER）
-- ============================================================

-- 任务定义
CREATE TABLE IF NOT EXISTS scheduled_task (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id         VARCHAR(100) NOT NULL COMMENT '所属用户（用户名）',
    name            VARCHAR(100) NOT NULL COMMENT '任务名（会话标题后缀"⏰ {name}"）',
    prompt          TEXT         NOT NULL COMMENT '任务指令（执行时自动前缀注入当前时间）',
    cron_expr       VARCHAR(100) NOT NULL COMMENT 'Spring 6 段 cron（秒 分 时 日 月 周；Asia/Shanghai）',
    enabled         TINYINT      NOT NULL DEFAULT 1 COMMENT '启用开关',
    tools_whitelist JSON         DEFAULT NULL COMMENT '任务级预授权工具（创建时明示风险；并入 allowedTools）',
    conversation_id BIGINT       DEFAULT NULL COMMENT '任务专属会话（懒创建）',
    next_run_at     DATETIME     DEFAULT NULL COMMENT '下次执行时间（列表展示 + DB CAS 认领凭据）',
    last_run_at     DATETIME     DEFAULT NULL,
    created_at      DATETIME     DEFAULT NULL,
    updated_at      DATETIME     DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_task_due (enabled, next_run_at),
    KEY idx_task_user (user_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='定时任务定义（P2-9）';

-- 执行记录（执行态管理）
CREATE TABLE IF NOT EXISTS scheduled_task_run (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    task_id              BIGINT       NOT NULL,
    user_id              VARCHAR(100) NOT NULL,
    status               VARCHAR(20)  NOT NULL COMMENT 'running/success/failed/skipped_budget',
    trace_id             VARCHAR(64)  DEFAULT NULL COMMENT '直通现有 trace 审计（usage/tool/rag 全链路可回查）',
    assistant_message_id BIGINT       DEFAULT NULL COMMENT '结果消息（跳转会话用）',
    duration_ms          BIGINT       DEFAULT NULL,
    error_message        VARCHAR(500) DEFAULT NULL,
    started_at           DATETIME     DEFAULT NULL,
    finished_at          DATETIME     DEFAULT NULL,
    created_at           DATETIME     DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_run_task (task_id, id),
    KEY idx_run_user_created (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='定时任务执行记录（P2-9）';

-- 站内通知（M2）
CREATE TABLE IF NOT EXISTS user_notification (
    id                     BIGINT       NOT NULL AUTO_INCREMENT,
    user_id                VARCHAR(100) NOT NULL,
    type                   VARCHAR(30)  NOT NULL DEFAULT 'task_result' COMMENT '事件类型（预留给后续审批提醒等）',
    title                  VARCHAR(200) NOT NULL,
    body                   VARCHAR(500) DEFAULT NULL COMMENT '回答摘要（截断 120 字）',
    target_conversation_id BIGINT       DEFAULT NULL COMMENT '点击跳转目标会话',
    read_at                DATETIME     DEFAULT NULL COMMENT '空=未读',
    created_at             DATETIME     DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_notif_user (user_id, id),
    KEY idx_notif_user_unread (user_id, read_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='站内通知事件（P2-9 M2）';

-- ============================================================
-- 手工迁移（CREATE TABLE IF NOT EXISTS 不会给已存在的表加列）：
-- 本地与生产均需执行一次（P2-10）：
-- ALTER TABLE chat_message ADD COLUMN trace_id VARCHAR(64) NULL COMMENT '全链路 traceId' AFTER id;
-- CREATE INDEX idx_chat_message_trace ON chat_message (trace_id);
--
-- 子代理嵌套归属（Agent-as-Tool）：tool_call_record 加列，已存在的库需执行一次：
-- ALTER TABLE tool_call_record ADD COLUMN agent_id VARCHAR(100) NULL COMMENT '发起该工具调用的 Agent：NULL=主 Agent，非空=子代理技能名' AFTER tool_name;
-- ============================================================
