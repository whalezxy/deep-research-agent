package com.example.deepresearch.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * /api/chat 与 /api/agent/approval SSE 的前端协议。
 *
 * <p>事件名称与 {@link #type()} 一致。ask 表示 Harness 已暂停工具循环，浏览器需要提交决定后恢复。
 */
public record ChatStreamEvent(
        String type,
        String userId,
        String sessionId,
        String delta,
        String toolName,
        String message,
        String answer,
        Boolean evolved,
        List<String> newSkills,
        Boolean memoryUpdated,
        String approvalId,
        List<ApprovalTool> approvalTools,
        Instant completedAt,
        ToolExecution toolExecution,
        ModelUsage modelUsage) {

    public ChatStreamEvent(String type, String userId, String sessionId, String delta,
            String toolName, String message, String answer, Boolean evolved,
            List<String> newSkills, Boolean memoryUpdated, String approvalId,
            List<ApprovalTool> approvalTools, Instant completedAt, ToolExecution toolExecution) {
        this(type, userId, sessionId, delta, toolName, message, answer, evolved, newSkills,
                memoryUpdated, approvalId, approvalTools, completedAt, toolExecution, null);
    }

    public ChatStreamEvent(String type, String userId, String sessionId, String delta,
            String toolName, String message, String answer, Boolean evolved,
            List<String> newSkills, Boolean memoryUpdated, String approvalId,
            List<ApprovalTool> approvalTools, Instant completedAt) {
        this(type, userId, sessionId, delta, toolName, message, answer, evolved,
                newSkills, memoryUpdated, approvalId, approvalTools, completedAt, null);
    }

    /** 来自框架真实事件；耗时从执行阶段开始计算，不包含人工等待。 */
    public record ToolExecution(String id, String name, String input, String output,
            String state, Long durationMs) {}

    public record ModelUsage(int inputTokens, int outputTokens, int cachedTokens, int reasoningTokens) {}

    public static ChatStreamEvent usage(String userId, String sessionId, ModelUsage usage) {
        return new ChatStreamEvent("model_usage", userId, sessionId, null, null, null,
                null, null, null, null, null, null, null, null, usage);
    }

    public static ChatStreamEvent subagent(String userId, String sessionId, String name, String message) {
        return new ChatStreamEvent("subagent", userId, sessionId, null, name, message,
                null, null, null, null);
    }

    public static ChatStreamEvent toolDetail(String userId, String sessionId, ToolExecution detail) {
        return new ChatStreamEvent("tool_detail", userId, sessionId, null, detail.name(),
                null, null, null, null, null, null, null, null, detail);
    }

    /** 兼容没有审批字段的旧版调用方。 */
    public ChatStreamEvent(
            String type,
            String userId,
            String sessionId,
            String delta,
            String toolName,
            String message,
            String answer,
            Boolean evolved,
            List<String> newSkills,
            Instant completedAt) {
        this(
                type,
                userId,
                sessionId,
                delta,
                toolName,
                message,
                answer,
                evolved,
                newSkills,
                null,
                null,
                null,
                completedAt);
    }

    public static ChatStreamEvent started(String userId, String sessionId) {
        return started(userId, sessionId, "已连接 HarnessAgent");
    }

    public static ChatStreamEvent started(String userId, String sessionId, String message) {
        return new ChatStreamEvent(
                "started",
                userId,
                sessionId,
                null,
                null,
                message,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public static ChatStreamEvent delta(String userId, String sessionId, String delta) {
        return new ChatStreamEvent(
                "delta", userId, sessionId, delta, null, null, null, null, null, null, null, null, null);
    }

    public static ChatStreamEvent tool(String userId, String sessionId, String toolName) {
        return new ChatStreamEvent(
                "tool",
                userId,
                sessionId,
                null,
                toolName,
                "正在调用工具：" + toolName,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public static ChatStreamEvent ask(
            String userId,
            String sessionId,
            String approvalId,
            List<ApprovalTool> approvalTools) {
        return new ChatStreamEvent(
                "ask",
                userId,
                sessionId,
                null,
                null,
                "请确认待执行的工具操作",
                null,
                null,
                null,
                null,
                approvalId,
                approvalTools,
                null);
    }

    public static ChatStreamEvent complete(
            String userId,
            String sessionId,
            String answer,
            boolean evolved,
            List<String> newSkills,
            boolean memoryUpdated) {
        return new ChatStreamEvent(
                "complete",
                userId,
                sessionId,
                null,
                null,
                null,
                answer,
                evolved,
                newSkills,
                memoryUpdated,
                null,
                null,
                Instant.now());
    }

    public static ChatStreamEvent complete(
            String userId,
            String sessionId,
            String answer,
            boolean evolved,
            List<String> newSkills) {
        return complete(userId, sessionId, answer, evolved, newSkills, false);
    }

    public static ChatStreamEvent error(String userId, String sessionId, String message) {
        return new ChatStreamEvent(
                "error",
                userId,
                sessionId,
                null,
                null,
                message,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /** 只展示待确认工具的必要信息；真实 ToolUseBlock 保留在服务端审批状态中。 */
    public record ApprovalTool(String id, String name, Map<String, Object> input) {
        public ApprovalTool {
            input = input == null ? Map.of() : Map.copyOf(input);
        }
    }
}
