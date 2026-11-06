package com.example.deepresearch.workspace;

import com.example.deepresearch.config.AgentProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/**
 * 初始化 Harness 工作区。
 *
 * <p>只在文件不存在时写入默认内容，因此 Harness 在运行中沉淀的 Memory 和 Skills 不会被应用重启覆盖。
 */
@Component
public class WorkspaceInitializer {

    private static final String DEFAULT_AGENTS_MD =
            """
            # Deep Research Agent

            你是一名严谨的深度研究智能体。复杂研究任务必须先拆解问题，再进行多轮检索、交叉验证、综合分析并给出来源。

            ## 研究工作流

            - 用规划工具提交研究计划，确认后委派证据检索和反证检查。研究至少覆盖三个互补检索式。
            - 区分事实、推断与不确定性；冲突信息必须明确指出。
            - 最终报告使用 Markdown，包含“结论、证据、局限、来源”四部分。
            - `tavily_search` 返回模拟资料，引用时标明“模拟搜索结果”。引用真实联网资料时注明实际调用的检索工具和来源。

            ## 自动进化协议

            完成一次复杂且成功的研究后，在输出最终答案前必须调用 `propose_skill`，把本次可复用的检索策略、验证步骤和分析模板提炼成 Skill。
            Skill 应面向一类任务而不是当前主题，名称使用 kebab-case，并包含清晰的触发条件和可执行步骤。简单问候或单一事实问答不创建 Skill。
            已有合适 Skill 时，应先加载复用并根据效果改进，避免创建同义重复 Skill。
            `propose_skill` 或 `skill_manage` 触发人工确认时必须等待；若用户拒绝，不能通过其他工具绕过，仍应完成报告并说明没有写入 Skill。

            ## 多租户边界

            - 当前调用的 `RuntimeContext.userId` 是唯一租户身份，只能使用该租户命名空间中的 Memory、Session 和私有 Skills。
            - 不得主动探测、读取或引用其他租户目录；共享 `workspace/skills/` 仅作为公共基础能力。
            - 长期记忆和自动生成 Skill 不得包含其他租户的信息。
            """;

    private final AgentProperties properties;
    private volatile Path initializedWorkspace;

    public WorkspaceInitializer(AgentProperties properties) {
        this.properties = properties;
    }

    public synchronized Path initialize() {
        if (initializedWorkspace != null) {
            return initializedWorkspace;
        }

        Path workspace = properties.getWorkspace().toAbsolutePath().normalize();
        try {
            Files.createDirectories(workspace);
            Files.createDirectories(workspace.resolve("skills"));
            Files.createDirectories(workspace.resolve("memory"));
            Files.createDirectories(workspace.resolve("memory/conversations"));
            Files.createDirectories(workspace.resolve("knowledge"));

            writeIfMissing(workspace.resolve("AGENTS.md"), DEFAULT_AGENTS_MD);
        } catch (IOException exception) {
            throw new IllegalStateException("无法初始化 Harness Workspace: " + workspace, exception);
        }

        initializedWorkspace = workspace;
        return workspace;
    }

    /**
     * 为租户创建空的运行层。独立 MEMORY.md 会阻止读取旧的全局记忆作为 fallback，
     * skills/ 与 memory/ 则由 Harness 在该 userId 命名空间内持续维护；session-history/ 保存前端
     * 可回放的用户消息和最终回答。
     */
    public synchronized Path initializeTenant(String requestedUserId) {
        String userId = TenantIdPolicy.requireValid(requestedUserId);
        Path workspace = initialize();
        Path tenantWorkspace = workspace.resolve(userId).normalize();
        if (!tenantWorkspace.startsWith(workspace)) {
            throw new IllegalArgumentException("userId 解析后的路径不在 Workspace 内");
        }

        try {
            Files.createDirectories(tenantWorkspace.resolve("skills"));
            Files.createDirectories(tenantWorkspace.resolve("memory"));
            Files.createDirectories(tenantWorkspace.resolve("session-history"));
            writeIfMissing(
                    tenantWorkspace.resolve("MEMORY.md"),
                    "# 长期记忆\n\n> 该租户的记忆由 AgentScope Harness 自动归并维护。\n");
        } catch (IOException exception) {
            throw new IllegalStateException("无法初始化租户 Workspace: " + tenantWorkspace, exception);
        }
        return tenantWorkspace;
    }

    private void writeIfMissing(Path target, String content) throws IOException {
        if (Files.notExists(target)) {
            Files.writeString(target, content, StandardCharsets.UTF_8);
        }
    }
}
