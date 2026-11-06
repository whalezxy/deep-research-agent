# Deep Research Agent

## 演示界面补充

- 在右侧“研究资产 → 技能库”点击技能名，可查看真实 `SKILL.md` 内容和原文；详情接口只允许读取当前租户及共享技能目录。
- 回答下方可展开工具详情，查看调用 ID、输入、返回文本摘要、框架状态及执行阶段耗时。摘要最多 8000 字符；耗时不包含人工审批等待，可能包含工具调度等待。
- 当 `load_skill_through_path` 成功读取 `SKILL.md` 时，展示“已读取技能内容”；展开参数中的 `skillId` 可确认具体技能。这个标记仅证明读取发生，不表示答案质量提升。
- 待审批时可以刷新页面，页面通过 `GET /api/agent/approvals?userId=...&sessionId=...` 恢复审批卡片；不自动批准、不额外调用模型，也不延长原来的 10 分钟有效期。后端重启后审批仍失效。
- 工具详情属于当前页面的实时记录，历史会话仍仅回放用户与助手文本；刷新恢复审批不会恢复此前的工具详情。
- 新增只读接口：`GET /api/agent/skills/detail?userId=...&relativePath=...`，其中路径取自状态接口返回的技能列表。

这是一个基于 **Spring Boot 3 + AgentScope Java 2.0 HarnessAgent + Vue 3** 的全栈演示。它用模拟 Tavily 工具展示多轮深度研究，并把成功的搜索、验证与分析模式自动沉淀为 Markdown Skill，直观呈现智能体“越用越聪明”的过程。

> `tavily_search` 是离线模拟工具，不会访问互联网。它适合演示工具调用、研究编排和 Skill 自动进化；真实业务应替换为 Tavily API 或企业搜索服务。

## 技术栈

- 后端使用 JDK 17、Spring Boot 3.5、Maven 和 AgentScope Java 2.0.4。
- 智能体：`HarnessAgent`、Workspace、双层记忆、会话持久化、Skill Manage Tool、ask/allow/deny 人工确认
- 前端：Vue 3、Vite、Element Plus、Markdown It、DOMPurify
- 模型：默认 `dashscope:qwen3-max`（可在 `application.yml` 中切换）

## AgentScope 2.0.4 能力配置

复杂研究先由 `plan_enter` 和 `plan_write` 创建计划，`plan_exit` 发出审批请求。计划获批后，主智能体委派 `evidence-scout` 和 `evidence-reviewer`。执行记录显示子任务进度与模型上报的 Token 用量。输入 `/status` 可让只读工具直接返回当前租户的研究资产概览。 [AgentScope 2.0.4 发布说明](https://github.com/agentscope-ai/agentscope-java/releases/tag/v2.0.4)

| 能力 | 启用方式 |
| --- | --- |
| 备用模型 | 主智能体的 DashScope 调用失败时默认尝试 `dashscope:qwen-plus`。通过 `DEEP_RESEARCH_FALLBACK_MODEL` 指定其他模型或设置为空。切换时服务端记录备用模型和错误类型。 |
| OpenAI Responses | 设置 `AGENTSCOPE_MODEL=openai-official:<模型名>` 和 `OPENAI_API_KEY`。同样可以作为备用模型。 |
| MCP | 设置 `DEEP_RESEARCH_MCP_URL` 为 Streamable HTTP 端点，按需设置 `DEEP_RESEARCH_MCP_TOKEN`。工具名称使用 `mcp_` 前缀。通过 `deep-research.features.mcp.tools` 列出允许的原始工具名，使用 `DEEP_RESEARCH_MCP_PROPAGATE_METADATA=true` 启用元数据透传。 |
| A2A | 设置 `DEEP_RESEARCH_A2A_URL` 为可访问 Agent Card 的服务基地址。在聊天中明确要求使用远程评审，主智能体会通过 `remote-reviewer` 委派。 |
| AG-UI | 设置 `DEEP_RESEARCH_AGUI_ENABLED=true` 和 `DEEP_RESEARCH_AGUI_TOKEN`，向 `POST /api/agui/run` 发送 `RunAgentInput` 和 Bearer 凭据。该入口提供独立的只读研究会话，由客户端传入历史和前端工具。 |
| Docker 沙箱 | 设置 `DEEP_RESEARCH_SANDBOX=docker` 和 `DEEP_RESEARCH_DOCKER_IMAGE`。运行环境需要能访问 Docker daemon。 |
| Kubernetes 沙箱 | 设置 `DEEP_RESEARCH_SANDBOX=kubernetes`、`DEEP_RESEARCH_KUBE_NAMESPACE` 和 `DEEP_RESEARCH_KUBE_WARM_POOL`。需要可访问配置好 SandboxWarmPool 的集群。 |
| E2B 沙箱 | 设置 `DEEP_RESEARCH_SANDBOX=e2b`、`E2B_API_KEY` 和 `DEEP_RESEARCH_E2B_TEMPLATE`。 |
| Redis 状态 | 设置 `DEEP_RESEARCH_STATE_BACKEND=redis` 和 `DEEP_RESEARCH_REDIS_URI`。 |
| PostgreSQL 状态 | 设置 `DEEP_RESEARCH_STATE_BACKEND=postgresql`、`DEEP_RESEARCH_POSTGRES_URL`（JDBC URL），按需设置 `DEEP_RESEARCH_POSTGRES_USER` 和 `DEEP_RESEARCH_POSTGRES_PASSWORD`。 |
| MongoDB 状态 | 设置 `DEEP_RESEARCH_STATE_BACKEND=mongodb` 和 `DEEP_RESEARCH_MONGODB_URI`。连接串应包含数据库名。 |
| 真实网页搜索 | 设置 `DEEP_RESEARCH_WEB_TOOLS=true` 和 `TAVILY_API_KEY`，并在聊天中要求联网检索。`tavily_search` 仍是离线模拟工具。 |
| DashScope 图片编辑 | 设置 `DEEP_RESEARCH_IMAGE_EDITING=true` 和现有的 DashScope 凭据。在聊天中提供可访问的 HTTPS 图片链接与修改要求。 |
| 最终回答过滤 | 设置 `DEEP_RESEARCH_FINAL_ANSWER_ONLY=true`。中间工具轮保留工具事件，最终文本在该轮模型响应结束后发送。 |
| 异步工具 | 子智能体可以通过 `agent_spawn` 的 `timeout_seconds=0` 后台运行，并通过 `wait_async_results` 收集指定任务。通过 `deep-research.features.async-tool-timeout`（如 `20s`）为普通工具启用超时转后台。 |

启用任一沙箱后，可要求 `sandbox-analyst` 生成 Markdown 或 CSV。它通过 `deliver_artifact` 将文件交付到 `workspace/<userId>/artifacts/`，并在回答中提供下载地址。三种沙箱按配置选用一种，任务使用独立沙箱会话。

远程状态后端负责 AgentState 和 Harness 的分布式状态，会话列表从选定后端读取可恢复会话。长记忆、Skill 和文本回放位于应用的 Workspace，待审批句柄保存在应用进程内。切换状态后端会读取该后端中的会话上下文。

启用 AG-UI 后，可用以下请求验证标准事件流。

```http
POST /api/agui/run
Authorization: Bearer <DEEP_RESEARCH_AGUI_TOKEN>
Content-Type: application/json
Accept: text/event-stream

{
  "threadId": "agui-demo-thread",
  "runId": "agui-demo-run",
  "messages": [{"id": "m1", "role": "user", "content": "用模拟检索找出两个研究线索"}],
  "tools": [],
  "context": [],
  "state": {},
  "forwardedProps": {}
}
```

## 项目结构

```text
.
├── pom.xml
├── src/main/java/com/example/deepresearch
│   ├── config/AgentConfiguration.java       # HarnessAgent 核心初始化
│   ├── tool/MockTavilySearchTool.java       # @Tool 模拟搜索工具
│   ├── tool/PermissionAwareTool.java         # 将 Skill 工具接入 ask/allow/deny 权限流
│   ├── agent/DeepResearchAgentService.java  # SSE 对话、恢复与进化差异检测
│   ├── agent/ApprovalRequestService.java    # 服务端待审批工具调用（按租户/会话隔离）
│   ├── workspace/                           # Workspace 初始化/状态/会话历史扫描
│   └── web/                                 # REST Controller 与 DTO（含会话回放）
├── workspace
│   ├── AGENTS.md                            # Workspace 人格与进化协议
│   ├── knowledge/RESEARCH_GUIDE.md
│   ├── skills/                              # 所有租户共享的基础 Skills
│   ├── memory/conversations/                # 按 userId/sessionId 隔离的 AgentState
│   ├── alice/session-history/               # Alice 可阅读的会话回放文本
│   ├── alice/                               # Alice 的 Memory、Session、自动 Skills
│   └── bob/                                 # Bob 的独立 Workspace 运行层
└── frontend                                 # Vue 3 应用
```

## Harness 自动进化如何工作

核心配置位于 `AgentConfiguration.java`：

```java
Path workspace = Path.of("./workspace").toAbsolutePath().normalize();

HarnessAgent.builder()
    .workspace(workspace)
    .disableWebTools()
    .enablePlanMode()
    .filesystem(new LocalFilesystemSpec()
        .project(Path.of(".").toAbsolutePath().normalize())
        .isolationScope(IsolationScope.USER))
    .compaction(CompactionConfig.builder().triggerMessages(18).keepMessages(6).build())
    .memory(MemoryConfig.builder()
        .flushTrigger(MemoryConfig.FlushTrigger.always())
        .build())
    .enableSkillManageTool(
        SkillManageConfig.builder().autoPromote(true).securityScan(true).build())
    .permissionContext(PermissionContextState.builder()
        .addAskRule("propose_skill", new PermissionRule(
            "propose_skill", null, PermissionBehavior.ASK, "deep-research-hitl"))
        .addAskRule("skill_manage", new PermissionRule(
            "skill_manage", null, PermissionBehavior.ASK, "deep-research-hitl"))
        .build())
    .build();
```

完成复杂研究后，`AGENTS.md` 和系统提示要求智能体调用 Harness 内置的 `propose_skill`。演示配置使用 `autoPromote=true`，但不会无条件写入：`propose_skill` 与直接使用的 `skill_manage` 都会先进入 `ASK` 状态，只有用户点选允许、并通过安全扫描后，Skill 才会写入：

```text
workspace/<userId>/skills/<skill-name>/SKILL.md
```

这是一个可见的 Harness 人工介入（Human-in-the-Loop）闭环：

```text
模型请求 plan_exit / propose_skill / skill_manage
  → Harness 发出 ask SSE 并暂停工具循环
  → 浏览器展示工具名与参数，用户允许或拒绝
  → POST /api/agent/approval 仅提交 approvalId + decision
  → 服务端回填 ConfirmResult，在同一租户、同一会话中继续
```

项目中的 `PermissionAwareTool` 为规划和 Skill 工具设置默认权限。智能体可先写计划草案，用户确认 `plan_exit` 后进入执行。待确认的 `ToolUseBlock` 保存在服务端内存，审批句柄有效期为 10 分钟，应用重启后失效。框架发出 `UserConfirmResultEvent` 后，服务端才移除句柄。若恢复请求在框架收到决定前失败，页面可重新获取有效审批。

`workspace/skills/` 是所有租户可见的共享基础层，`workspace/<userId>/skills/` 是租户私有层，同名 Skill 会覆盖共享版本。长期记忆在后台刷新，前端每 4 秒拉取当前租户的状态并显示实际写入结果。`complete` 中的 `memoryUpdated` 表示回答完成时已经观测到的变更。

> 自动提升适合本地演示。本项目已在写入前增加一次性人工确认；生产环境还应使用默认草稿目录 `skills/_drafts/`、持久化审批记录、身份认证与更细粒度的 `SkillPromotionGate` 灰度发布策略。

## 双层记忆与落盘位置

- 默认短期会话上下文位于 `workspace/memory/conversations/<userId>/<sessionId>/`。
- 长期事实流水账：`workspace/<userId>/memory/YYYY-MM-DD.md`。
- 归并长期记忆：`workspace/<userId>/MEMORY.md`。
- 原始会话审计日志：`workspace/<userId>/agents/deep-research-agent/sessions/`。
- UI 会话回放：`workspace/<userId>/session-history/<sessionId>.json`（只保存用户消息和最终回答，不保存内部推理）。
- 自动生成 Skills：`workspace/<userId>/skills/<skill-name>/SKILL.md`。

本示例使用 `IsolationScope.USER`，`RuntimeContext` 同时携带 `userId` 与 `sessionId`。同一用户的多个 Session 共享长期记忆和私有 Skills，不同用户之间彼此隔离。租户 ID 在后端统一规范为小写，并经过路径穿越、Workspace 保留目录和 Windows 设备名校验。

从旧版全局模式升级时，根目录中遗留的 `workspace/MEMORY.md` 不会被租户读取；应用会先为每个租户创建独立的 `workspace/<userId>/MEMORY.md`，避免旧记忆跨租户回退。

## 启动后端

启动需要 JDK 17+、Maven 3.9+ 和可用的 DashScope 密钥。

设置 `DASHSCOPE_API_KEY` 环境变量，或在项目根目录的 `config/deep-research-local.properties` 中写入以下配置。

```properties
DASHSCOPE_API_KEY=你的密钥
```

`application.yml` 提供公共配置，Spring Boot 会按需导入本机文件。`.gitignore` 会排除本机凭据文件。

启动：

```bash
mvn spring-boot:run
```

后端默认监听 `http://localhost:8080`。可通过环境变量覆盖模型和工作区：

```bash
export AGENTSCOPE_MODEL=dashscope:qwen-max
export DEEP_RESEARCH_WORKSPACE=./workspace
```

## 启动前端

前置条件：Node.js 18+。

```bash
cd frontend
npm install
npm run dev
```

打开 `http://localhost:5173`。Vite 会把 `/api` 请求代理到 `http://localhost:8080`。

## REST API

### 流式对话（SSE）

```http
POST /api/chat
Content-Type: application/json
Accept: text/event-stream

{
  "message": "深度研究 AgentScope Java 2.0 的 Harness 架构",
  "userId": "alice",
  "sessionId": "demo-session"
}
```

接口会保持连接并依次推送以下 SSE 事件：

```text
event: started
data: {"type":"started","userId":"alice","sessionId":"demo-session"}

event: delta
data: {"type":"delta","delta":"正在输出的文本片段"}

event: tool
data: {"type":"tool","toolName":"tavily_search","message":"正在调用工具：tavily_search"}

event: ask
data: {"type":"ask","userId":"alice","sessionId":"demo-session","approvalId":"...","approvalTools":[{"id":"call_...","name":"propose_skill","input":{"name":"...","description":"...","body":"..."}}]}

event: complete
data: {"type":"complete","userId":"alice","sessionId":"demo-session","answer":"...Markdown 报告...","evolved":true,"newSkills":["multi-source-framework-research"],"memoryUpdated":true,"completedAt":"2026-08-25T08:00:00Z"}
```

`ask` 表示本轮暂停，前端通过新的 SSE 请求恢复审批。聊天和审批恢复各自使用独立的 `AgentRun`，同一租户的同一会话一次只运行一轮。服务端检测到连接断开或超时时，会取消这条连接对应的运行。异常时会发送 `error` 事件。

可在 PowerShell 中用 `curl.exe -N` 直接观察事件流：

```powershell
curl.exe -N -X POST "http://localhost:8080/api/chat" `
  -H "Content-Type: application/json" `
  -H "Accept: text/event-stream" `
  --data-raw '{"message":"请做一次深度研究并沉淀 Skill","userId":"alice","sessionId":"sse-demo"}'
```

### 人工确认并恢复研究（SSE）

收到 `ask` 后，使用该事件返回的 `approvalId` 恢复同一轮研究：

```http
POST /api/agent/approval
Content-Type: application/json
Accept: text/event-stream

{
  "approvalId": "ask 事件中的审批编号",
  "userId": "alice",
  "sessionId": "demo-session",
  "decision": "allow"
}
```

`decision` 接受 `allow` 或 `deny`。拒绝研究计划后可以补充方向并重新规划。拒绝 Skill 变更时，智能体可以继续完成报告。`approvalId` 与 `userId`、`sessionId` 绑定，已处理、过期或跨租户的请求会被拒绝。

### 智能体状态

```http
GET /api/agent/status?userId=alice
```

返回 Alice 的租户 Workspace 路径、有效 Skill 列表、长期记忆摘要、持久会话数量和租户独立的模拟 Tavily 调用统计。

状态响应还包含 `recommendations`。它是一个不额外调用模型的确定性推荐层：优先根据当前租户的 ACTIVE Skill 生成复用问题，其次根据长期记忆和最近检索主题生成验证问题，空租户则返回入门问题。因此切换租户或完成研究后，前端“建议问题”和首屏快捷提示会动态变化。

### 会话历史

```http
GET /api/agent/sessions?userId=alice
GET /api/agent/sessions/<sessionId>?userId=alice
```

第一个接口返回当前租户的会话摘要列表，第二个接口返回可阅读的用户消息和最终回答。点击前端“历史”即可恢复，同一 `sessionId` 的上下文由选定的 AgentState 后端提供。只有底层状态的旧会话也会出现在列表中，详情提示“可继续对话”。

## 建议演示步骤

1. 选择租户 `alice`，输入：`请记住，我的项目代号是 Aurora。`；完成后点击“历史”，确认会话已经可回放。
2. 在同一会话输入 `请深度研究 RAG 与 Agentic Search 的架构取舍，进行多轮模拟检索、交叉验证，并沉淀一个可复用 Skill。` 在 `plan_exit` 确认卡片审阅计划，批准后观察两个研究子任务的进度。
3. 在后续 `propose_skill` 卡片检查候选 Skill。点“允许本次”后，同一条消息会继续，最终报告、研究资产和会话回放会更新。
4. 再发起另一项复杂研究，在 `ASK` 卡片点“拒绝”。最终报告仍会完成，但右侧 Skill 数量和 `workspace/alice/skills/` 不应因这次拒绝而增加；时间线会留下“用户已拒绝本次变更”。
5. 切换到 `bob`，询问：`我的项目代号是什么？` Bob 不应知道 Aurora；Bob 的私有 Memory、搜索计数和私有 Skills 从零开始，共享基础 Skills 仍正常可见。
6. 让 Bob 完成另一项深度研究并允许写入，观察文件只写入 `workspace/bob/`，不会改变 Alice 的私有 Skills 和 Memory。切回 `alice`，查看右侧“建议问题”，再从“历史”恢复旧会话追问项目代号或原研究结论。

## 安全提示

本项目采用 Harness 本地文件系统模式，仅用于可信的本地演示。`IsolationScope.USER` 能展示 AgentState、Memory、Session 和 Skill 的逻辑命名空间隔离，但本地主机 Shell 不是强安全边界。生产环境处理不可信或互不信任的租户时，应切换到按用户隔离的 Docker、Kubernetes、远程存储或云沙箱，并配置 Tool Permission 与 Skill 审批策略。

此外，本 Demo 为方便演示允许前端直接选择 `userId`，这不等同于身份认证。生产接口必须从已经验证的 JWT/Session 中解析租户身份，不能信任请求体或查询参数传入的 `userId`。
