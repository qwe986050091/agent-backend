# 功能待办与演进规划（TODO）

> 维护说明：本清单基于 2026-09-16 对四个仓库（agent / agent_backend / agent_frontend / agent_mobile）的代码盘点。
> 状态约定：`[ ]` 未开始 · `[~]` 进行中 · `[x]` 已完成。完成一项勾一项，并在文末「变更记录」补一行。

## 一、现状基线（已完成，勿重复建设）

- 流式对话（SSE）+ 多会话管理 + 中断生成
- 技能体系：自定义技能挂载、内置技能（calculator/datetime/web/code-runner）、MCP 接入
- RAG：pgvector 向量 + pg_trgm 关键词混合检索、RRF 融合、bge-reranker 精排、编号引用、Tika 解析 PDF/Office
- 安全：高危工具人工审批（含"会话始终允许"）、SSRF 双重防护、systemd 沙箱执行 Python、内部 token 鉴权
- 运营：赞/踩反馈闭环（含管理后台）、token 用量与成本统计、管理员白名单
- 可观测：Prometheus + Grafana（14 面板）、全链路 trace 落库、Resilience4j 熔断重试

---

## 二、优先级总览

| 优先级 | 编号 | 事项 | 价值 | 工作量 |
|---|---|---|---|---|
| P0 | T1 | 聊天 Markdown 渲染 + 代码高亮 | 高 | 小 |
| P0 | T2 | 回复操作栏（复制 / 重新生成 / 编辑提问重发） | 高 | 小 |
| P0 | T3 | 移动端补齐高危工具审批 | 高（功能断裂修复） | 中 |
| P1 | T4 | 移动端补齐反馈与引用展示 | 高 | 小 |
| P1 | T5 | 用户配额 / 预算限额闭环 | 高 | 中 |
| P1 | T6 | 管理后台：用户管理 + 成本看板 | 中高 | 中 |
| P1 | T7 | RAG 召回调试台 | 高（调优基础设施） | 中 |
| P1 | T8 | MCP 服务界面化管理 | 高 | 中 |
| P2 | T9 | 知识库分组 / 多知识库 + 检索范围选择 | 中高 | 大 |
| P2 | T10 | URL 网页入库 + 文档版本与重建索引 | 中 | 中 |
| P2 | T11 | 知识库异步索引 + 状态展示 | 中 | 中 |
| P2 | T12 | 中文关键词检索升级（zhparser） | 中（技术债） | 中 |
| P2 | T13 | 会话搜索 / 置顶 / 导出 / 只读分享 | 中 | 中 |
| P2 | T14 | 账号自助（改密 / 昵称头像 / 注销） | 中 | 小 |
| P2 | T15 | Prompt 与模型参数后台化（含模型可选） | 中 | 中 |
| P2 | T16 | 扩展内置工具（飞书系列 / 文档导出 / 定时提醒） | 中 | 中 |
| P2 | T17 | 技能模板市场 | 中 | 大 |
| P2 | T18 | 工作流编排 | 中 | 大 |
| P2 | T19 | 移动端用量页 / 推送通知 | 低中 | 中 |
| P1 | T20 | ToolGrantStore 持久化 | 中（重启丢失授权） | 小 |
| P1 | T21 | MySQL + pgvector 定时备份与恢复演练 | 高（单点风险） | 中 |
| P2 | T22 | CI/CD + 镜像化自动部署 | 中 | 中 |

---

## 三、P0：聊天体验补齐（每次对话都受益）

### T1 聊天 Markdown 渲染 + 代码高亮 `[ ]`
- 现状：agent_frontend 无任何 markdown 依赖，LLM 回复中的代码块/表格/列表按纯文本显示。
- 改动点：
  - agent_frontend：引入 `react-markdown` + `remark-gfm` + `rehype-highlight`（浅色主题，与现有极简风格一致）；封装 `MarkdownMessage` 组件替换 ChatPage 中的纯文本渲染。
  - 注意流式输出期间的增量渲染性能（useMemo 或节流）。
- 验收：代码块带高亮与语言标签；表格、有序/无序列表、引用块正常；流式过程不卡顿；XSS 安全（不允许裸 html 注入）。

### T2 回复操作栏：复制 / 重新生成 / 编辑提问重发 `[ ]`
- 现状：ChatPage 仅有"停止生成"。
- 改动点：
  - 前端：助手消息悬浮操作条（复制内容、重新生成）；用户消息支持编辑后重发。
  - backend/agent：重新生成 = 以原 user 消息再跑一轮（trace 新生成，旧回答的处理策略需定：覆盖 or 保留多版本，建议先保留最新、旧记录标记 regenerated）。
- 验收：三项操作可用；重新生成走 SSE 且正常落库；trace/feedback 与消息的关联不错位。

### T3 移动端补齐高危工具审批 `[ ]`（**功能断裂，优先修**）
- 现状：agent_mobile 完全没有 approval 处理；手机端使用 code-runner / HTTP 等 CONFIRM 技能时，agent 发出 `approval-request` 后流程直接挂死。
- 改动点：
  - agent_mobile：SSE 解析增加 `approval-request` 事件；底部弹审批卡片（工具名、风险等级、参数预览 ≤500 字、拒绝/允许、"本会话始终允许"勾选）；决策调 `POST /api/chat/approvals/{id}/decision`。
  - 参考 PC：agent_frontend/src/api.ts 的 `ApprovalRequestData` / `decideApproval`。
- 验收：手机端触发高危工具时弹窗；允许后继续执行、拒绝后给出明确提示；勾选 remember 后同会话不再询问。

---

## 四、P1：运营闭环与基础设施

### T4 移动端补齐反馈与引用展示 `[ ]`
- 反馈：助手消息下加赞/踩条（接口 `/api/feedback` 已就绪），点踩带原因字典；history 水合已支持。
- 引用：消息正文 `[n]` 渲染为可点角标，弹出/展开对应召回片段（PC 端 CitationText 可作交互参考）。
- 验收：赞/踩/取消/互斥与 PC 行为一致；引用角标可看到来源片段文本。

### T5 用户配额 / 预算限额闭环 `[ ]`
- 现状：BillingService 已算出每轮费用并落库，但没有任何拦截与提醒。
- 改动点：
  - backend：配置每用户/全局 月度 token 或费用预算（默认值 + 按用户覆盖）；对话前校验累计用量，超限返回明确错误码（如 429 QUOTA_EXCEEDED）；阈值 80% 时通过 usage 事件/响应头提醒。
  - 前端：用量页展示预算进度条；超限友好提示。
- 验收：超限被拦截且不产生新 LLM 调用；额度按月重置；管理员可配置。

### T6 管理后台：用户管理 + 成本看板 `[ ]`
- 现状：管理端只有反馈后台（/admin/feedback）。
- 改动点：
  - `/api/admin/users`：用户列表、搜索、封禁/解封、按用户查明细与累计成本。
  - `/api/admin/costs`：按日/模型/用户的成本聚合（数据来源 llm_usage_record）。
  - 前端新增两个后台页面，挂管理员菜单。
- 验收：封禁用户无法登录/对话；成本看板数字与 usage 页对得上。

### T7 RAG 召回调试台 `[ ]`
- 现状：rag_recall_record 已记录 channel（vector/keyword/both）与 rerank_score，但无可视化入口。
- 改动点：
  - 输入问题 + 目标用户/知识库 → 展示向量 Top10、关键词 Top10、RRF 融合顺序、rerank 后分数与最终截断（maxResults=4）全过程。
  - 实现上优先复用 agent 现有检索组件加一个 debug 接口（内部 token 保护），backend 透传，前端管理员页呈现。
- 验收：能一眼看出"某条为何被召回/为何被截断"；分数与真实问答链路一致。

### T8 MCP 服务界面化管理 `[ ]`
- 现状：MCP server 配置写死在 agent 的 application.yml（McpProperties），增删需改配置重启。
- 改动点：
  - MCP 配置持久化到 backend MySQL；agent 启动/变更时拉取；支持连接测试、启用停用、按用户授权可见性。
  - 前端技能页加 MCP 服务管理区。
- 验收：新增一个 MCP server 无需重启即可连通并在工具列表出现；停用后工具立即不可用；鉴权沿用 deny-by-default 登记。

### T20 ToolGrantStore 持久化 `[ ]`
- 现状：会话级授权存内存（ToolGrantStore），backend 重启后全部丢失。
- 改动点：落 MySQL（表：user_id + tool + scope + 过期时间）或 Redis；启动加载、决策时写入。
- 验收：重启后"本会话始终允许"仍生效；到期/登出后失效。

### T21 数据库定时备份与恢复演练 `[ ]`
- 现状：新机单机运行 MySQL + pgvector，无备份，风险最高。
- 改动点：mysqldump + pg_dump 每日落盘并异地拷贝（可传老服务器）；保留 7~14 天；写恢复脚本并做一次真实恢复演练。
- 验收：备份产物可在空库完整恢复（含反馈、trace、rag_embeddings）。

---

## 五、P2：产品深化（按需排期）

### 知识库方向
- T9 `[ ]` 知识库分组/多知识库：文档归属知识库，对话时选择检索范围（需改 rag_embeddings 元数据与检索过滤、前端选择器、移动端同步）。
- T10 `[ ]` URL 网页入库（复用 agent 的 SafeHttpClient/WebFetch，注意 SSRF 规则复用）；同名文档支持版本更新与重建索引。
- T11 `[ ]` 异步索引：上传立即返回任务，后台切块向量化，文档增加 processing/ready/failed 状态与进度轮询（解决 20MB 文件同步阻塞）。
- T12 `[ ]` 中文检索升级：pg_trgm 对中文退化为全表扫描，评估 zhparser（SCWS）中文全文检索替换 bigram 方案，保留现有 RRF/rerank 链路。

### 对话与账号方向
- T13 `[ ]` 会话搜索（按标题/消息内容）、置顶、单会话导出 Markdown、只读分享链接（签名 token，免登录）。
- T14 `[ ]` 账号自助：修改密码、昵称/头像、注销账号（注销需级联清理用户全部数据）。
- T15 `[ ]` Prompt/模型后台化：system prompt、温度等参数可配置可版本化；对话支持选择模型；可结合反馈数据做 prompt 效果对比。

### 智能体能力方向
- T16 `[ ]` 扩展工具：飞书消息/日历/云文档（可参考环境内 Lark 能力）、Word/Excel/PDF 导出、定时提醒。
- T17 `[ ]` 技能模板市场：官方模板一键挂载、技能分享。
- T18 `[ ]` 工作流编排：固定多步任务（定时总结、数据搬运），比自由 ReAct 更可控。
- T19 `[ ]` 移动端：用量统计页；推送通知通道（配合主动消息/定时提醒）。

### 工程效能
- T22 `[ ]` CI/CD：Java 侧 `mvnw clean package`（规避增量编译污染）+ 前端 pnpm 构建，流水线化部署；进一步做容器镜像。

---

## 六、迭代建议（落地顺序）

1. **第一迭代（P0）**：T1 → T2 → T3。投入小、感知最强，其中 T3 算移动端缺陷修复。
2. **第二迭代（P1）**：T20、T21（风险兜底）→ T5、T6（成本闭环）→ T4（移动端对齐）。
3. **第三迭代**：T7、T8（平台能力上限）。
4. **之后**：按实际使用反馈从 P2 池子里取，知识库深化（T9~T12）优先于技能市场/工作流（T17/T18，投入大）。

---

## 七、技术成长路线（G 系列，以学技术为目标，非业务刚需）

> 定位：T 系列是"产品需要做什么"，G 系列是"做什么能提升技术深度"。允许引入新组件，但每一步必须解决一个真实问题，不为装而装。
> 编号：G1~G5，状态同样用 `[ ]` / `[~]` / `[x]` 跟踪。

### 选型前提（关于 Spark）

- 业务体量（单机 MySQL、个人项目）不足以支撑 Hadoop/HDFS/YARN/Hive 全家桶，**不上重集群**。
- Spark 用 local / standalone 直读 MySQL JDBC、写 Parquet/OLAP，这是多数公司的真实用法；优先 PySpark（补 Python 数据栈），有余力可尝试 Scala（Spark 母语 + 函数式）。
- 百万行以内先用 **DuckDB + Parquet**（零依赖嵌入式 OLAP），跑不动再演进到 Spark——演进判断本身就是学习目标。
- 踩坑预防：PySpark 先冻结版本矩阵（Python 3.10/3.11 + 固定版本 PySpark + 对应 Java），启动时检查 JAVA_HOME/SPARK_HOME，不用过宽的版本号。

### G1 LLM 运营数据实时数仓 `[ ]`（主推，数据地基已现成）

- 数据源（无需造数据）：llm_usage_record、rag_recall_record、tool_call_record、message_feedback、chat_message。
- 目标架构：

  ```
  MySQL Binlog → Flink CDC → Kafka（或 Redpanda 轻量替代）
    → ODS：Parquet（MinIO / S3 兼容存储）
    → 批：Spark 每日 T+1 聚合   流：Flink 实时指标
    → ADS：ClickHouse / StarRocks
    → Grafana 直连 或 React + ECharts 实时大屏
  ```

- 交付物：实时 token 消耗 / 成本 / QPS / 错误率 / 工具调用分布 / 点踩率大屏。
- 学到：CDC、消息队列、流批一体、数仓分层（ODS/DWD/DWS/ADS）、OLAP、任务调度。
- 资源约束：中间件优先跑在开发机 WSL2 或新增数据专用机，不挤占生产机内存（4c4g 上 JVM 堆已紧张）。
- 验收：从 binlog 变更到大盘可见端到端打通；T+1 与实时两条链路数字对得上。

### G2 RAG/LLM 离线评估平台 `[ ]`（差异化最强，AI + 数据交叉）

- 用点踩反馈 + trace 构造评估集（问题 / 召回片段 / 回答 / 人工标签）。
- Spark 批量评估：召回命中率、MRR、rerank 前后增益、答非所问率、LLM-as-a-judge 自动打分。
- 与 T15（Prompt/模型后台化）联动做 prompt 与参数（chunk size、topK、阈值）A/B 对比。
- 延伸：每日对话抽样打标回流，沉淀为后续 LoRA 微调的数据集。
- 验收：一次参数变更能用评估集报告量化差异（提升/退化多少），而非凭感觉。

### G3 Temporal 工作流编排 `[ ]`（Java 后端深度）

- 现状：异步靠 ThreadPoolExecutor（SSE、RAG 索引），审批状态内存态（T20 相关）。
- 引入 Temporal（Java SDK 成熟）：审批等待、批量文档索引、定时报告、T18 多步工作流改为 durable workflow——重启不丢、自动重试、状态可查询。
- 学到：事件溯源、持久化编排、分布式可靠性；配合 agent 无状态化做多 worker 横向扩展。
- 验收：backend 重启后，进行中的审批/索引任务自动恢复执行，不丢不重。

### G4 云原生改造 `[ ]`

- 路径：Docker Compose（本地一键起全栈）→ k3s/kind 单节点 K8s → Helm Chart。
- 实践 Ingress、HPA、ConfigMap/Secret、滚动发布与回滚；agent 无状态架构天然适配。
- 验收：新机器按文档一条命令拉起环境；一次滚动发布期间对话请求无中断。

### G5 自托管模型推理 `[ ]`（按需，可能产生少量租机成本）

- 用 vLLM 或 llama.cpp 部署开源模型（Qwen / DeepSeek 小尺寸），按时租 GPU 机体验即可。
- 学到：推理引擎、KV cache、连续批处理、量化；打通"自托管模型作为 fallback/专用于某技能"的配置。
- 验收：agent 可切换到自托管模型完成一轮带工具调用的对话；记录自托管 vs 外部 API 的延迟与成本对比。

### G 系列建议节奏

| 阶段 | 内容 | 周期感 |
|---|---|---|
| 1 | G1 起步：DuckDB + Parquet 对 trace 表做 T+1 分析（先回答真实问题，如"上月哪个模型最烧钱"） | 1~2 周 |
| 2 | 升级 Spark local（PySpark），补数仓分层 + 调度（DolphinScheduler / Airflow） | 3~4 周 |
| 3 | Flink CDC + Kafka + ClickHouse 实时化，完成 G1 端到端闭环 | 1~2 月 |
| 4 | 在数仓之上建 G2 评估平台，形成差异化能力 | 持续迭代 |
| 并行支线 | G3（替换审批/异步链路）、G4（部署改造）按需穿插；G5 有明确需求再启动 | — |

---

## 八、变更记录

| 日期 | 内容 |
|---|---|
| 2026-09-16 | 初版：盘点四仓库后建立 22 项待办（P0×3 / P1×7 / P2×12） |
| 2026-09-16 | 追加第七章「技术成长路线」：G1~G5（数仓/评估平台/Temporal/云原生/自托管推理）及分阶段节奏 |
