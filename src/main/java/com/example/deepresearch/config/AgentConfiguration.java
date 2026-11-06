package com.example.deepresearch.config;

import com.example.deepresearch.tool.MockTavilySearchTool;
import com.example.deepresearch.tool.PermissionAwareTool;
import com.example.deepresearch.workspace.ResearchArtifacts;
import com.example.deepresearch.workspace.WorkspaceInitializer;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.middleware.FinalAnswerFilterMiddleware;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.tool.PlanModeTools;
import io.agentscope.harness.agent.tool.SkillManageConfig;
import java.nio.file.Path;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** AgentScope HarnessAgent 的核心装配。 */
@Configuration
public class AgentConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentConfiguration.class);

    private static final String SYSTEM_PROMPT =
            """
            你是 Deep Research Agent，一名擅长问题拆解、多源检索、交叉验证和证据综合的研究员。

            对复杂研究任务：
            1. 在不同轮次依次调用 plan_enter、plan_write、plan_exit。用 plan_write 保存问题、证据标准和检索分工。
               通过 plan_exit 提交用户确认，在 rationale 中附上可供用户审阅的计划摘要。
               如果用户拒绝计划，停在规划阶段，等待其补充方向。
            2. 计划获批后，使用 agent_spawn 将证据检索交给 evidence-scout，反证检查交给 evidence-reviewer。
               可在同一批调用中并行委派。若返回 task_id，使用 wait_async_results 的 task_ids 等待所需结果。
               用户要求后台执行时，可用 timeout_seconds=0 委派并在汇总前等待结果。
               至少覆盖三组互补查询。子任务失败时，主智能体可以自行检索补齐。
               用户明确要求远程评审或生成研究文件时，可分别委派给已配置的 remote-reviewer 或 sandbox-analyst。
               若沙箱交付成功，在报告中原样附上工具返回的下载地址。
            3. 对照结果，明确事实、推断、冲突和资料缺口。用 Markdown 写中文报告，包含结论、证据、局限和来源。
            4. tavily_search 返回模拟资料，引用时标明“模拟搜索结果”。用户要求联网时，使用已配置的 web_search 或 MCP 检索工具获取真实资料。
            5. 研究成功后、给出最终回答前，调用 propose_skill 沉淀可迁移的研究方法。

            Skill 只保留可迁移的方法，不写入当前用户的敏感信息或一次性结论。
            propose_skill 与 skill_manage 会触发人工确认：出现确认请求后必须等待结果。
            若用户拒绝，不得尝试通过其他工具绕过审批；应保留研究结论，并说明该 Skill 未写入。
            多租户环境中，只能使用 RuntimeContext 当前 userId 对应的 Memory、Session 和私有 Skills，
            不得探测或引用其他租户目录；workspace/skills 中的共享 Skills 除外。
            对简单闲聊或单事实问答直接回答，不要滥用搜索，也不要创建 Skill。
            用户输入 /status 或只要求研究资产概览时，只调用 research_status。
            用户要求编辑图片时，在工具可用的情况下调用 dashscope_image_to_image，并使用用户给出的 HTTPS 图片链接。
            """;

    static Toolkit mockSearchToolkit(MockTavilySearchTool tavilySearchTool) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tavilySearchTool);
        return toolkit;
    }

    @Bean
    public Model researchModel(AgentProperties properties) {
        return resolveModel(properties, properties.getModel());
    }

    @Bean
    public HarnessAgent harnessAgent(
            AgentProperties properties,
            ResearchFeatures features,
            WorkspaceInitializer workspaceInitializer,
            ResearchIntegrations integrations,
            ResearchArtifacts artifacts,
            AgentStateStore researchStateStore,
            ObjectProvider<DistributedStore> distributedStores,
            Toolkit researchToolkit,
            MockTavilySearchTool tavilySearchTool,
            Model researchModel) {
        Path workspace = workspaceInitializer.initialize();

        /*
         * USER 隔离会把运行期 Memory、Session 和自动生成的 Skills 路由到
         * ./workspace/<userId>/ 下。Harness 会另外把 workspace/skills 注册为共享
         * Skill Repository；project 仍指向项目根目录，避免把 Workspace 运行数据暴露到共享下层。
         */
        LocalFilesystemSpec filesystem =
                new LocalFilesystemSpec()
                        .project(Path.of(".").toAbsolutePath().normalize())
                        .isolationScope(IsolationScope.USER)
                        .inheritEnv(false);

        PermissionContextState permissionContext =
                PermissionContextState.builder()
                        // 任意 Skill 变更会暂停当前循环，由前端提交 allow/deny 后再恢复。
                        .addAskRule(
                                "propose_skill",
                                new PermissionRule(
                                        "propose_skill",
                                        null,
                                        PermissionBehavior.ASK,
                                        "deep-research-hitl"))
                        .addAskRule(
                                "skill_manage",
                                new PermissionRule(
                                        "skill_manage",
                                        null,
                                        PermissionBehavior.ASK,
                                        "deep-research-hitl"))
                        .build();

        HarnessAgent.Builder builder =
                HarnessAgent.builder()
                        .name(properties.getName())
                        .sysPrompt(SYSTEM_PROMPT)
                        .model(researchModel)
                        .workspace(workspace)
                        .filesystem(filesystem)
                        .stateStore(researchStateStore)
                        .toolkit(researchToolkit)
                        .enablePlanMode()
                        .subagentFactory(
                                "evidence-scout",
                                "执行多组检索并整理可引用证据",
                                name -> researchWorker(name,
                                        "用 tavily_search 发出至少两组互补查询，逐条给出模拟来源、发现和证据缺口。",
                                        researchModel, tavilySearchTool))
                        .subagentFactory(
                                "evidence-reviewer",
                                "独立查找反例、冲突及验证盲区",
                                name -> researchWorker(name,
                                        "用 tavily_search 独立检索反例或冲突，标注模拟来源，说明哪些结论需要额外验证。",
                                        researchModel, tavilySearchTool))
                        .maxIters(properties.getMaxIterations())
                        .compaction(
                                CompactionConfig.builder()
                                        .triggerMessages(properties.getCompactionTriggerMessages())
                                        .keepMessages(properties.getCompactionKeepMessages())
                                        .keepTokens(0)
                                        .build())
                        .memory(
                                MemoryConfig.builder()
                                        .flushTrigger(MemoryConfig.FlushTrigger.always())
                                        .consolidationMinGap(Duration.ofSeconds(10))
                                        .build())
                        // autoPromote 决定“批准后”是否直接成为 ACTIVE Skill；审批由 permissionContext 接管。
                        .enableSkillManageTool(
                                SkillManageConfig.builder()
                                        .autoPromote(properties.isSkillAutoPromote())
                                        .securityScan(true)
                                        .build())
                        .permissionContext(permissionContext);

        if (!features.webTools()) {
            builder.disableWebTools();
        }
        if (features.finalAnswerOnly()) {
            builder.middleware(new FinalAnswerFilterMiddleware());
        }
        if (features.asyncToolTimeout() != null) {
            builder.asyncToolTimeout(features.asyncToolTimeout());
        }
        distributedStores.ifAvailable(builder::distributedStore);
        integrations.configureOptionalWorkers(builder, features, workspace, researchModel, artifacts);
        String primaryModel = properties.getModel().strip();
        String configuredFallback = features.fallbackModel().strip();
        String fallbackModel = "auto".equalsIgnoreCase(configuredFallback)
                ? (primaryModel.startsWith("dashscope:") ? "dashscope:qwen-plus" : "")
                : configuredFallback;
        if (!fallbackModel.isEmpty() && !fallbackModel.equals(primaryModel)) {
            builder.fallbackModel(resolveModel(properties, fallbackModel))
                    .failoverListener((primary, error) -> LOGGER.warn(
                            "主模型调用失败，切换到 {} ({})",
                            fallbackModel, error.getClass().getSimpleName()));
        }
        HarnessAgent agent = builder.build();

        installPermissionAdapter(agent, PlanModeTools.PLAN_ENTER, PermissionBehavior.ALLOW);
        installPermissionAdapter(agent, PlanModeTools.PLAN_WRITE, PermissionBehavior.ALLOW);
        installPermissionAdapter(agent, PlanModeTools.PLAN_EXIT, PermissionBehavior.ASK);
        installPermissionAdapter(agent, "propose_skill", PermissionBehavior.ASK);
        installPermissionAdapter(agent, "skill_manage", PermissionBehavior.ASK);
        return agent;
    }

    private void installPermissionAdapter(HarnessAgent agent, String toolName, PermissionBehavior behavior) {
        Toolkit toolkit = agent.getToolkit();
        AgentTool original = toolkit.getTool(toolName);
        if (original == null || !toolkit.removeToolIfSame(toolName, original)) {
            throw new IllegalStateException("无法设置工具权限 " + toolName);
        }
        toolkit.registerAgentTool(new PermissionAwareTool(original, behavior));
    }

    /**
     * DashScope 凭据通过 Spring 配置读取，支持环境变量和本机配置文件。
     * 其他模型前缀仍保留 AgentScope ModelRegistry 的标准解析方式。
     */
    private Model resolveModel(AgentProperties properties, String configuredModel) {
        String modelId = configuredModel.strip();
        if (!modelId.startsWith("dashscope:")) {
            return ModelRegistry.resolve(modelId);
        }

        String apiKey = properties.getDashscopeApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "请通过 DASHSCOPE_API_KEY 环境变量或 config/deep-research-local.properties 配置 DashScope 密钥");
        }

        String modelName = modelId.substring("dashscope:".length()).strip();
        if (modelName.isBlank()) {
            throw new IllegalArgumentException("DashScope 模型名不能为空，例如 dashscope:qwen-plus");
        }

        return DashScopeChatModel.builder()
                .apiKey(apiKey.strip())
                .modelName(modelName)
                .stream(true)
                .enableThinking(false)
                .formatter(new DashScopeChatFormatter())
                .build();
    }

    private ReActAgent researchWorker(
            String name, String prompt, Model model, MockTavilySearchTool searchTool) {
        return ReActAgent.builder()
                .name(name)
                .sysPrompt(prompt)
                .model(model)
                .toolkit(mockSearchToolkit(searchTool))
                .maxIters(5)
                .build();
    }
}
