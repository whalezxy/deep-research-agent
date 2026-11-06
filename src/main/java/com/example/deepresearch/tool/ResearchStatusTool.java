package com.example.deepresearch.tool;

import com.example.deepresearch.web.dto.AgentStatusResponse;
import com.example.deepresearch.workspace.WorkspaceStatusService;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.Tool;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
public class ResearchStatusTool {

    private final WorkspaceStatusService statusService;

    public ResearchStatusTool(WorkspaceStatusService statusService) {
        this.statusService = statusService;
    }

    @Tool(name = "research_status", description = "用户输入 /status 或只要求当前租户研究资产概览时调用。",
            readOnly = true, stateInjected = true, returnDirect = true)
    public Mono<ToolResultBlock> status(AgentState state) {
        if (state == null || state.getUserId() == null) {
            return Mono.just(ToolResultBlock.error("当前请求缺少租户身份"));
        }
        String userId = state.getUserId();
        return Mono.fromSupplier(() -> format(statusService.snapshot(userId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ToolResultBlock format(AgentStatusResponse status) {
        return ToolResultBlock.text("""
                ### 当前租户的研究资产

                - 活跃 Skill %d 项
                - 长期记忆日志 %d 份
                - 持久会话 %d 个
                - 模拟搜索 %d 次
                """.formatted(status.evolution().activeSkillCount(), status.memory().dailyFileCount(),
                status.memory().conversationCount(), status.search().totalSearches()));
    }
}
