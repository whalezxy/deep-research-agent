package com.example.deepresearch.web.dto;

import java.time.Instant;
import java.util.List;

/** 当前租户可恢复会话的轻量目录，不暴露其他租户数据。 */
public record SessionHistoryResponse(String userId, List<SessionSummary> sessions) {

    public record SessionSummary(
            String sessionId,
            String title,
            Instant createdAt,
            Instant updatedAt,
            int messageCount) {}
}
