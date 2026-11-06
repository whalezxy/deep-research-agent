package com.example.deepresearch.agent;

import com.example.deepresearch.agent.ApprovalRequestService.PendingApproval;
import com.example.deepresearch.agent.ApprovalRequestService.ResolvedApproval;
import com.example.deepresearch.config.AgentProperties;
import com.example.deepresearch.web.dto.ChatStreamEvent;
import com.example.deepresearch.web.dto.ChatStreamEvent.ApprovalTool;
import com.example.deepresearch.web.dto.ChatStreamEvent.ModelUsage;
import com.example.deepresearch.web.dto.ChatStreamEvent.ToolExecution;
import com.example.deepresearch.workspace.SessionHistoryService;
import com.example.deepresearch.workspace.SessionIdPolicy;
import com.example.deepresearch.workspace.TenantIdPolicy;
import com.example.deepresearch.workspace.WorkspaceStatusService;
import io.agentscope.core.agent.AgentRun;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.event.UserConfirmResultEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.tool.PlanModeTools;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 封装 HarnessAgent SSE 调用、会话回放以及人工确认后的恢复。 */
@Service
public class DeepResearchAgentService {

    private static final Set<String> TERMINAL_EVENTS = Set.of("ask", "complete", "error");

    private final HarnessAgent harnessAgent;
    private final AgentProperties properties;
    private final WorkspaceStatusService workspaceStatusService;
    private final SessionHistoryService sessionHistoryService;
    private final ApprovalRequestService approvalRequestService;
    private final ConcurrentMap<SessionKey, Object> activeSessions = new ConcurrentHashMap<>();

    public DeepResearchAgentService(
            HarnessAgent harnessAgent,
            AgentProperties properties,
            WorkspaceStatusService workspaceStatusService,
            SessionHistoryService sessionHistoryService,
            ApprovalRequestService approvalRequestService) {
        this.harnessAgent = harnessAgent;
        this.properties = properties;
        this.workspaceStatusService = workspaceStatusService;
        this.sessionHistoryService = sessionHistoryService;
        this.approvalRequestService = approvalRequestService;
    }

    /** 启动一轮新的研究；若 Harness 请求确认，SSE 会以 ask 事件暂停而不会发送 complete。 */
    public Flux<ChatStreamEvent> streamChat(
            String message, String requestedSessionId, String requestedUserId) {
        String userId = TenantIdPolicy.requireValid(requestedUserId);
        String sessionId =
                requestedSessionId == null || requestedSessionId.isBlank()
                        ? UUID.randomUUID().toString()
                        : SessionIdPolicy.requireValid(requestedSessionId);
        String normalizedMessage = message == null ? "" : message.strip();
        if (normalizedMessage.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        requireNoPendingApproval(userId, sessionId);
        return inSession(
                userId,
                sessionId,
                () -> {
                    requireNoPendingApproval(userId, sessionId);
                    Map<String, String> skillsBefore =
                            workspaceStatusService.activeSkillVersions(userId);
                    String memoryBefore = workspaceStatusService.memoryVersion(userId);
                    sessionHistoryService.appendUserMessage(userId, sessionId, normalizedMessage);
                    return execute(
                            userId,
                            sessionId,
                            new UserMessage(normalizedMessage),
                            skillsBefore,
                            memoryBefore,
                            "已连接 HarnessAgent",
                            null);
                });
    }

    /**
     * 处理前端的 allow/deny，并把 ConfirmResult 放回同一个 Harness 会话继续执行。
     *
     * <p>客户端只提交 approvalId；待执行的 ToolUseBlock 始终来自服务端内存中的待审批记录。
     */
    public Flux<ChatStreamEvent> resumeApproval(
            String approvalId, String requestedUserId, String requestedSessionId, String decision) {
        PendingApproval pending = approvalRequestService
                .resolve(approvalId, requestedUserId, requestedSessionId, decision)
                .pending();
        return inSession(
                pending.userId(),
                pending.sessionId(),
                () -> executeApproval(approvalRequestService.resolve(
                        approvalId, pending.userId(), pending.sessionId(), decision)));
    }

    private Flux<ChatStreamEvent> executeApproval(ResolvedApproval resolved) {
        PendingApproval pending = resolved.pending();
        boolean confirmed = resolved.decision() == ApprovalRequestService.Decision.ALLOW;

        List<ConfirmResult> results =
                pending.toolCalls().stream()
                        .map(toolCall -> new ConfirmResult(
                                confirmed, toolCall, null, confirmed ? null : denialReason(toolCall)))
                        .toList();
        UserMessage continuation =
                UserMessage.builder()
                        .textContent(
                                confirmed
                                        ? "用户已允许待确认操作，请继续原研究。"
                                        : "用户拒绝了待确认操作，请按照工具返回的拒绝结果处理。")
                        .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, results))
                        .build();

        return execute(
                pending.userId(),
                pending.sessionId(),
                continuation,
                pending.skillsBefore(),
                pending.memoryBefore(),
                confirmed ? "已允许操作，正在恢复 HarnessAgent" : "已拒绝操作，正在恢复 HarnessAgent",
                pending);
    }

    private String denialReason(ToolUseBlock toolCall) {
        return PlanModeTools.PLAN_EXIT.equals(toolCall.getName())
                ? "用户拒绝了研究计划。请停在规划阶段，等待用户补充方向。"
                : "用户拒绝了这项工具操作。";
    }

    public List<ChatStreamEvent> pendingApprovals(String userId, String sessionId) {
        return approvalRequestService.pendingFor(userId, sessionId).stream()
                .map(pending -> ChatStreamEvent.ask(pending.userId(), pending.sessionId(),
                        pending.approvalId(), describeToolCalls(pending.toolCalls())))
                .toList();
    }

    private void requireNoPendingApproval(String userId, String sessionId) {
        if (!approvalRequestService.pendingFor(userId, sessionId).isEmpty()) {
            throw new IllegalArgumentException("当前会话有待审批操作，请先恢复并处理审批");
        }
    }

    private Flux<ChatStreamEvent> inSession(
            String userId, String sessionId, Supplier<Flux<ChatStreamEvent>> execution) {
        SessionKey key = new SessionKey(userId, sessionId);
        return Flux.defer(() -> {
            Object lease = new Object();
            if (activeSessions.putIfAbsent(key, lease) != null) {
                return Flux.error(new IllegalArgumentException("当前会话正在运行，请等待本轮结束"));
            }
            return Flux.defer(execution)
                    .doOnNext(event -> {
                        // 在客户端收到 ask 或 complete 前释放会话，允许它立即发起下一次请求。
                        if (TERMINAL_EVENTS.contains(event.type())) {
                            activeSessions.remove(key, lease);
                        }
                    })
                    .doFinally(signal -> activeSessions.remove(key, lease));
        });
    }

    private record SessionKey(String userId, String sessionId) {}

    private static final class ToolTrace {
        String input = "";
        String output = "";
        Long startedNanos;
    }

    private Flux<ChatStreamEvent> execute(
            String userId,
            String sessionId,
            UserMessage input,
            Map<String, String> skillsBefore,
            String memoryBefore,
            String startedMessage,
            PendingApproval pendingApproval) {
        RuntimeContext context =
                RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
        AgentRun<AgentEvent> run = harnessAgent.prepareRun(List.of(input), context);
        AtomicReference<String> finalAnswer = new AtomicReference<>("");
        StringBuilder streamedText = new StringBuilder();
        AtomicReference<List<ToolUseBlock>> askingToolCalls =
                new AtomicReference<>(List.of());
        AtomicReference<Boolean> permissionAsking = new AtomicReference<>(false);
        Map<String, ToolTrace> traces = new ConcurrentHashMap<>();
        if (pendingApproval != null) {
            for (ToolUseBlock call : pendingApproval.toolCalls()) {
                ToolTrace trace = new ToolTrace();
                trace.input = abbreviate(Objects.toString(call.getInput(), ""), 8000);
                traces.put(call.getId(), trace);
            }
        }

        Flux<ChatStreamEvent> stream =
                run.stream()
                        .timeout(properties.getCallTimeout())
                        .<ChatStreamEvent>handle(
                                (event, sink) -> {
                                    if (event instanceof ModelCallEndEvent modelEvent) {
                                        ChatUsage usage = modelEvent.getUsage();
                                        if (usage != null) {
                                            sink.next(ChatStreamEvent.usage(userId, sessionId,
                                                    new ModelUsage(usage.getInputTokens(), usage.getOutputTokens(),
                                                            usage.getCachedTokens(), usage.getReasoningTokens())));
                                        }
                                        return;
                                    }
                                    String source = event.getSource();
                                    if (source != null) {
                                        String name = source.substring(source.lastIndexOf('/') + 1);
                                        if (event instanceof AgentStartEvent) {
                                            sink.next(ChatStreamEvent.subagent(userId, sessionId, name, "开始处理子任务"));
                                        } else if (event instanceof AgentEndEvent) {
                                            sink.next(ChatStreamEvent.subagent(userId, sessionId, name, "子任务流已结束"));
                                        }
                                        return;
                                    }
                                    if (event instanceof UserConfirmResultEvent
                                            && pendingApproval != null) {
                                        approvalRequestService.consume(pendingApproval);
                                        return;
                                    }
                                    if (event instanceof RequireUserConfirmEvent confirmEvent) {
                                        List<ToolUseBlock> calls = confirmEvent.getToolCalls();
                                        if (calls != null && !calls.isEmpty()) {
                                            askingToolCalls.set(List.copyOf(calls));
                                            permissionAsking.set(true);
                                        }
                                        return;
                                    }
                                    if (event instanceof ToolCallDeltaEvent e) {
                                        ToolTrace trace = traces.computeIfAbsent(e.getToolCallId(), key -> new ToolTrace());
                                        trace.input = abbreviate(trace.input + Objects.toString(e.getDelta(), ""), 8000);
                                        return;
                                    }
                                    if (event instanceof ToolResultStartEvent e) {
                                        ToolTrace trace = traces.computeIfAbsent(e.getToolCallId(), key -> new ToolTrace());
                                        trace.startedNanos = System.nanoTime();
                                        // 工具参数未通过事件传入时，从当前会话状态补全。
                                        if (trace.input.isBlank()) {
                                            AgentState state = harnessAgent.getDelegate().getAgentState(context);
                                            if (state != null && state.getContext() != null) {
                                                state.getContext().stream().filter(Objects::nonNull)
                                                        .filter(msg -> msg.getContent() != null)
                                                        .flatMap(msg -> msg.getContent().stream())
                                                        .filter(ToolUseBlock.class::isInstance).map(ToolUseBlock.class::cast)
                                                        .filter(call -> Objects.equals(call.getId(), e.getToolCallId()))
                                                        .findFirst().ifPresent(call -> trace.input = abbreviate(
                                                                Objects.toString(call.getInput(), ""), 8000));
                                            }
                                        }
                                        sink.next(ChatStreamEvent.toolDetail(userId, sessionId,
                                                new ToolExecution(e.getToolCallId(), e.getToolCallName(), trace.input, "", "RUNNING", null)));
                                        return;
                                    }
                                    if (event instanceof ToolResultTextDeltaEvent e) {
                                        ToolTrace trace = traces.computeIfAbsent(e.getToolCallId(), key -> new ToolTrace());
                                        trace.output = abbreviate(trace.output + Objects.toString(e.getDelta(), ""), 8000);
                                        return;
                                    }
                                    if (event instanceof ToolResultEndEvent e) {
                                        ToolTrace trace = traces.computeIfAbsent(e.getToolCallId(), key -> new ToolTrace());
                                        Long duration =
                                                trace.startedNanos == null
                                                                || e.getState() == ToolResultState.DENIED
                                                        ? null
                                                        : (System.nanoTime() - trace.startedNanos) / 1_000_000;
                                        sink.next(ChatStreamEvent.toolDetail(userId, sessionId,
                                                new ToolExecution(e.getToolCallId(), e.getToolCallName(), trace.input,
                                                        trace.output, Objects.toString(e.getState(), "UNKNOWN"), duration)));
                                        return;
                                    }
                                    if (event instanceof TextBlockDeltaEvent textEvent) {
                                        String delta = textEvent.getDelta();
                                        if (delta != null && !delta.isEmpty()) {
                                            streamedText.append(delta);
                                            sink.next(ChatStreamEvent.delta(userId, sessionId, delta));
                                        }
                                    } else if (event instanceof ToolCallStartEvent toolEvent) {
                                        sink.next(
                                                ChatStreamEvent.tool(
                                                        userId,
                                                        sessionId,
                                                        toolEvent.getToolCallName()));
                                    } else if (event instanceof AgentResultEvent resultEvent) {
                                        Msg result = resultEvent.getResult();
                                        if (result == null) {
                                            return;
                                        }
                                        String answer = result.getTextContent();
                                        finalAnswer.set(answer == null ? "" : answer);
                                        if (result.getGenerateReason()
                                                == GenerateReason.PERMISSION_ASKING) {
                                            permissionAsking.set(true);
                                        }
                                    }
                                })
                        .concatWith(
                                Mono.fromSupplier(
                                        () ->
                                                finishExecution(
                                                        userId,
                                                        sessionId,
                                                        context,
                                                        finalAnswer.get(),
                                                        streamedText.toString(),
                                                        permissionAsking.get(),
                                                        askingToolCalls.get(),
                                                        skillsBefore,
                                                        memoryBefore)));

        return stream
                .startWith(ChatStreamEvent.started(userId, sessionId, startedMessage))
                .onErrorResume(
                        exception ->
                                Flux.just(
                                        ChatStreamEvent.error(
                                                userId, sessionId, safeMessage(exception))))
                .doFinally(signal -> run.cancel());
    }

    private ChatStreamEvent finishExecution(
            String userId,
            String sessionId,
            RuntimeContext context,
            String finalAnswer,
            String streamedText,
            boolean permissionAsking,
            List<ToolUseBlock> capturedToolCalls,
            Map<String, String> skillsBefore,
            String memoryBefore) {
        if (permissionAsking) {
            List<ToolUseBlock> toolCalls =
                    capturedToolCalls == null || capturedToolCalls.isEmpty()
                            ? askingToolCallsFromState(context)
                            : capturedToolCalls;
            if (toolCalls.isEmpty()) {
                throw new IllegalStateException("Harness 请求人工确认，但未找到待审批工具调用");
            }
            PendingApproval pending =
                    approvalRequestService.register(
                            userId, sessionId, toolCalls, skillsBefore, memoryBefore);
            return ChatStreamEvent.ask(
                    userId, sessionId, pending.approvalId(), describeToolCalls(toolCalls));
        }

        Map<String, String> skillsAfter = workspaceStatusService.activeSkillVersions(userId);
        List<String> newSkills = detectChangedSkills(skillsBefore, skillsAfter);
        String answer = finalAnswer == null ? "" : finalAnswer;
        if (answer.isBlank()) {
            answer = streamedText == null ? "" : streamedText;
        }
        sessionHistoryService.appendAssistantMessage(userId, sessionId, answer);
        boolean memoryUpdated =
                !Objects.equals(memoryBefore, workspaceStatusService.memoryVersion(userId));
        return ChatStreamEvent.complete(
                userId, sessionId, answer, !newSkills.isEmpty(), newSkills, memoryUpdated);
    }

    private List<ToolUseBlock> askingToolCallsFromState(RuntimeContext context) {
        try {
            AgentState state = harnessAgent.getDelegate().getAgentState(context);
            if (state == null || state.getContext() == null) {
                return List.of();
            }
            return state.getContext().stream()
                    .filter(Objects::nonNull)
                    .flatMap(message ->
                            message.getContent() == null
                                    ? java.util.stream.Stream.<io.agentscope.core.message.ContentBlock>empty()
                                    : message.getContent().stream())
                    .filter(ToolUseBlock.class::isInstance)
                    .map(ToolUseBlock.class::cast)
                    .filter(call -> call.getState() == ToolCallState.ASKING)
                    .toList();
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private List<ApprovalTool> describeToolCalls(List<ToolUseBlock> toolCalls) {
        return toolCalls.stream()
                .map(
                        call ->
                                new ApprovalTool(
                                        call.getId(),
                                        call.getName(),
                                        publicInput(call.getInput())))
                .toList();
    }

    private Map<String, Object> publicInput(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        input.forEach(
                (key, value) -> {
                    Object safeValue = value == null ? "" : value;
                    if (safeValue instanceof String text) {
                        safeValue = abbreviate(text, 2_000);
                    }
                    copy.put(key, safeValue);
                });
        return Collections.unmodifiableMap(copy);
    }

    private List<String> detectChangedSkills(
            Map<String, String> skillsBefore, Map<String, String> skillsAfter) {
        Set<String> changedSkillNames = new LinkedHashSet<>();
        skillsAfter.forEach(
                (fingerprint, name) -> {
                    if (!skillsBefore.containsKey(fingerprint)) {
                        changedSkillNames.add(name);
                    }
                });
        List<String> newSkills = new ArrayList<>(changedSkillNames);
        newSkills.sort(String::compareTo);
        return List.copyOf(newSkills);
    }

    private String safeMessage(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "智能体流式调用失败" : message;
    }

    private String abbreviate(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(1, limit - 1)) + "…";
    }
}
