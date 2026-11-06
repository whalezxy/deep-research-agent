package com.example.deepresearch.tool;

import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.message.ToolResultBlock;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * 将 Harness 内置的 AgentTool 适配成可参与 AgentScope 权限引擎的 ToolBase。
 *
 * <p>保留 Harness 工具的执行逻辑，为规划和 Skill 工具声明应用的默认权限策略。
 */
public final class PermissionAwareTool extends ToolBase {

    private final AgentTool delegate;
    private final PermissionBehavior behavior;

    public PermissionAwareTool(AgentTool delegate, PermissionBehavior behavior) {
        super(builderFor(delegate));
        this.delegate = require(delegate);
        this.behavior = behavior;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return delegate.callAsync(param);
    }

    /**
     * 工具自带策略也适用于旧会话。已有的显式拒绝和审批规则由框架优先判断。
     */
    @Override
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> input, PermissionContextState context) {
        return Mono.just(
                PermissionDecision.builder()
                        .behavior(behavior)
                        .message(behavior == PermissionBehavior.ASK ? "该工具需要用户确认" : "允许保存规划草案")
                        .decisionReason(behavior == PermissionBehavior.ASK ? "safety: research-hitl" : "research-plan")
                        .build());
    }

    @Override
    public Boolean getStrict() {
        return delegate.getStrict();
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return delegate.getOutputSchema();
    }

    private static AgentTool require(AgentTool tool) {
        if (tool == null) {
            throw new IllegalArgumentException("待适配的 AgentTool 不能为空");
        }
        return tool;
    }

    private static Map<String, Object> parametersOf(AgentTool tool) {
        Map<String, Object> parameters = require(tool).getParameters();
        return parameters == null ? Map.of() : parameters;
    }

    private static ToolBase.Builder builderFor(AgentTool tool) {
        AgentTool checked = require(tool);
        return ToolBase.builder()
                .name(checked.getName())
                .description(checked.getDescription())
                .inputSchema(parametersOf(checked))
                .readOnly(checked.isReadOnly())
                .concurrencySafe(false)
                .externalTool(false)
                .stateInjected(false);
    }
}
