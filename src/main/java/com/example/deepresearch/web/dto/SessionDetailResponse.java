package com.example.deepresearch.web.dto;

import java.time.Instant;
import java.util.List;

/** 可用于前端回放的会话文本；AgentScope 的底层状态仍由 JsonFileAgentStateStore 管理。 */
public record SessionDetailResponse(
        String userId,
        String sessionId,
        String title,
        Instant createdAt,
        Instant updatedAt,
        List<Message> messages) {

    public record Message(String role, String content, Instant createdAt) {}
}
