下面是一条可完整演示“会话持久化、长期记忆、Mock Tavily、多轮研究、Skill 自动进化、Skill 复用、多租户隔离、ask/allow/deny 人工确认”的测试链路。

开始前不要重启后端，否则右侧检索次数会归零。触发 `ASK` 后请在 10 分钟内处理；允许刷新页面恢复审批卡片，但后端重启会使审批失效。前端租户分别使用：

```text
showcase-alice
showcase-bob
```

## 1. Alice：建立长期记忆

在租户选择器中输入 `showcase-alice`，发送：

```text
请将以下内容作为仅属于当前租户的长期记忆保存，供未来新会话使用。
不要创建 Skill，也不要将这些内容写入通用方法论。

- 项目代号：Aurora-2026
- 输出偏好：中文、结论先行、必须单列风险
- 当前目标：为企业知识库建设 AI Agent
- 预算约束：首期 PoC 不超过 30 万人民币

请确认你记住了哪些信息。
```

观察右侧：

- “持久会话”至少变为 `1`；
- “长期记忆”出现内容或更新时间变化。

等待约 10～15 秒，点击聊天区右上角“新会话”按钮，然后发送：

```text
根据当前租户的长期记忆，告诉我项目代号、输出偏好、目标和预算约束。
```

这一步验证：新 Session 不继承普通聊天气泡，但同一租户的长期记忆仍可恢复。

## 2. Alice：触发 Mock Tavily、多轮研究和自动 Skill

继续在 `showcase-alice` 下发送：

```text
这是一次必须完成的复杂深度研究演示。

请研究“企业知识库场景中 RAG 与 Agentic Search 的选型方法”，并严格执行：

1. 先调用 tavily_search 至少三次，检索角度分别覆盖：
   - 架构定义与适用边界；
   - 成本、延迟与评估指标；
   - 失败案例、风险与治理要求。
2. 对结果进行交叉分析，区分事实、推断和不确定性。
3. 使用 Markdown 输出：结论、证据、局限、来源、推荐落地路线。
4. 明确说明搜索结果是离线模拟数据，不得宣称完成真实联网核验。
5. 这是一次成功的复杂研究；最终回答前必须调用 propose_skill，
   沉淀一个通用、不含本租户隐私信息的 Skill，
   建议名称：enterprise-knowledge-agent-research。
```

观察左侧：

- 会出现“正在检索、验证并提炼可复用方法”；
- 回答下方会出现 `执行记录`，展示持久化会话、Tavily Mock、证据综合和 Skill 提升阶段；
- 调用 `propose_skill` 时先出现黄色 `需要确认 / 待确认` 卡片，此时不会收到 `complete`，Skill 目录也不会写入本次候选文件；
- 点击“允许本次”后，同一条消息继续接收 SSE，最终报告才会完成，并应标注“模拟搜索结果”；
- 成功时出现 `工作区更新` 消息。

观察右侧：

- “模拟检索”应至少增加 3；
- “最近一次模拟检索”显示最后检索词；
- “技能库”出现 `enterprise-knowledge-agent-research`，标签为 `showcase-alice` 和 `ACTIVE`；
- “建议问题”会从固定入门问题切换为 Skill 复用、长期记忆验证或上次主题反方证据等租户专属问题；
- 中央状态短暂显示 `技能库已更新`。

点击“允许本次”并收到 `complete` 后，文件层面可验证：

```text
workspace/showcase-alice/skills/enterprise-knowledge-agent-research/SKILL.md
workspace/showcase-alice/MEMORY.md
workspace/memory/conversations/showcase-alice/
workspace/showcase-alice/session-history/<sessionId>.json
```

### 2.1 Alice：验证 DENY 与审批租户隔离

上一步点击允许后，`ConfirmResult(true, toolCall)` 已恢复原 Harness 循环。现在再新建一个 Alice 会话，发送：

```text
请完成一次复杂深度研究，至少调用 tavily_search 三次，最后调用 propose_skill
沉淀名为 approval-deny-demo 的通用研究方法。输出结论、证据、局限和来源，
并明确这是离线模拟搜索结果。
```

当出现 `ASK` 卡片时，先确认卡片列出了 `propose_skill` 和模型生成的参数；在点击拒绝前，也可以按下面的方式先验证跨租户提交。之后回到 Alice 页面点击“拒绝”：

- 模型应收到权限拒绝并继续完成研究报告；
- 这次不会新增 `approval-deny-demo` Skill；
- `执行记录` 会同时保留“等待人工确认”和“用户已拒绝本次变更”。

若要验证审批的租户边界，可在 Alice 的 `ASK` 卡片出现时复制浏览器 Network 中的 `approvalId`，用 Bob 的身份提交：

```powershell
$body = '{"approvalId":"Alice 的 approvalId","userId":"showcase-bob","sessionId":"Bob 的 sessionId","decision":"allow"}'
Invoke-RestMethod "http://localhost:8080/api/agent/approval" -Method Post -ContentType "application/json" -Headers @{ Accept = "text/event-stream" } -Body $body
```

该请求应被拒绝（审批不属于当前租户或会话），Alice 的待审批请求仍可回到 Alice 页面正常处理。不要把 Alice 的审批编号写入 Bob 的测试数据。

## 3. Alice：查看历史并恢复会话

点击聊天区右上角“历史”，选择刚才的会话。页面会重新展示用户问题和最终回答；同一 `sessionId` 仍由 Harness 的 `JsonFileAgentStateStore` 提供上下文。然后发送：

```text
请总结我们刚才关于 RAG 与 Agentic Search 的研究结论，
并结合我的项目代号 Aurora-2026 给出建议问题。
```

预期：旧消息能在页面回放，智能体还能接续上一次 Session 的研究上下文，并结合长期记忆。刷新浏览器后再次打开“历史”也能恢复同一列表。

## 4. Alice：验证 Skill 复用

再点击“新会话”，保持租户仍为 `showcase-alice`，发送：

```text
请使用当前已有的研究方法，快速为“客服知识库智能体”制定一份技术调研计划。

要求：复用已有通用研究框架，不要创建新的 Skill；
输出问题拆解、检索策略、评估指标和风险清单。
```

预期：

- 仍能使用 Alice 已沉淀的方法；
- 右侧 Alice 私有 Skill 仍存在；
- 不应出现新的 Skill 进化提示。
- 右侧“建议问题”会继续优先推荐复用 Alice 的已有 Skill。

## 5. Bob：验证租户隔离

切换租户为 `showcase-bob`，先观察右侧面板：Bob 的持久会话、检索次数、长期记忆和动态推荐应是新的独立状态。

发送：

```text
我的项目代号是什么？我的预算是多少？请直接基于你的长期记忆回答。
```

预期：Bob 不应知道 `Aurora-2026` 或 30 万预算。

再发送：

```text
这是一次复杂研究。请研究“客服知识库智能体的首期 PoC 方案”，
至少调用 tavily_search 三次，并在最终回答前调用 propose_skill，
沉淀一个通用 Skill，建议名称为 customer-service-agent-poc-framework。

注意：不要引用其他租户的信息。
```

先等待 Bob 出现黄色 `ASK` 卡片，确认待确认工具、参数和租户为 `showcase-bob`，再点击“允许本次”。在点击允许前，Bob 的 Skill 目录不应写入候选文件；允许后才观察 Skill 和最终报告。

预期：

- Bob 的检索计数独立增长；
- Bob 生成自己的私有 Skill；
- 文件写入 `workspace/showcase-bob/skills/`；
- Alice 的目录和 Skill 不会被修改。

## 6. 回到 Alice：验证隔离与恢复

切回 `showcase-alice`，发送：

```text
请告诉我我的项目代号、预算约束，
并列出当前可用的企业知识库研究方法。
```

预期：只能得到 Alice 的 Aurora-2026、30 万预算和 Alice 的私有 Skill 相关内容，不应出现 Bob 的客服 PoC 内容。

## 7. 可选：展示“共享 Skill”标签

当前自动生成的 Skill 是租户私有的。若还想展示右侧的 `共享` 标签，可在编辑器中创建：

```text
workspace/skills/shared-research-checklist/SKILL.md
```

内容示例：

```markdown
---
name: shared-research-checklist
description: 所有租户可复用的研究报告质量检查清单
---

# Shared Research Checklist

1. 明确研究边界。
2. 至少包含支持性和反方证据。
3. 区分事实、推断与待验证项。
4. 输出结论、证据、局限和来源。
```

分别切换 Alice、Bob 并刷新右侧面板，应都能看到 `shared-research-checklist`，其范围标签为“共享”；各自自动沉淀的 Skill 则仍显示各自租户名。

最后用状态接口交叉核验：

```powershell
Invoke-RestMethod "http://localhost:8080/api/agent/status?userId=showcase-alice"
Invoke-RestMethod "http://localhost:8080/api/agent/status?userId=showcase-bob"
```

对比两份结果中的 `memory`、`search`、`evolution.skills` 和 `tenantWorkspace`，即可完整展示多租户隔离效果。

也可以查看：

```powershell
Invoke-RestMethod "http://localhost:8080/api/agent/sessions?userId=showcase-alice"
Invoke-RestMethod "http://localhost:8080/api/agent/sessions/<sessionId>?userId=showcase-alice"
```

会话历史接口只返回当前租户的回放文本；不同租户的 session ID、消息、Memory 和私有 Skill 不会混在一起。

## 补充验证：技能详情、工具记录与刷新恢复

1. 点击右侧技能库中的技能名，检查抽屉中的路径、作用域、Markdown 和完整原文；与对应 SKILL.md 对照。
2. 新建会话，发送：“请使用已有 Skill，先通过 load_skill_through_path 读取其 SKILL.md，再按其中的方法补充研究，不创建新技能。”
3. 展开工具详情，确认输入中的 skillId/path、返回摘要、状态和耗时。只有成功读取技能内容才应显示“已读取技能内容”；失败调用不得出现该标记。没有加载事件时，不应把回答自述当作复用证据。
4. 发起需 propose_skill 审批的研究，看到待确认卡片后刷新页面。确认同一租户、同一会话的卡片恢复，再点允许；应继续收到工具结果及最终回答。
5. 再次触发审批并刷新，这次选择拒绝。该次 Skill 操作不应执行，工具记录应反映拒绝状态。
6. 使用另一租户 ID 查询该会话的 approvals 接口，应返回空列表；读取其他租户技能路径应被拒绝。共享技能允许查看。
7. 超过 10 分钟或重启后端再刷新，不应恢复失效审批；重新发起研究。已处理的 approvalId 不能再次领取。
8. 工具详情暂不写入会话回放文件；刷新或打开历史后，旧工具详情缺失属于当前实现边界。
