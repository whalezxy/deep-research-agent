package com.example.deepresearch.web.dto;

import java.time.Instant;
import java.util.List;

/** “智能体大脑”面板所需的 Workspace 快照。 */
public record AgentStatusResponse(
        String agentName,
        String model,
        String userId,
        String workspace,
        String tenantWorkspace,
        String lifecycle,
        Instant observedAt,
        Evolution evolution,
        Memory memory,
        Search search,
        List<Recommendation> recommendations) {

    /** 兼容没有动态推荐字段的旧状态消费者。 */
    public AgentStatusResponse(
            String agentName,
            String model,
            String userId,
            String workspace,
            String tenantWorkspace,
            String lifecycle,
            Instant observedAt,
            Evolution evolution,
            Memory memory,
            Search search) {
        this(
                agentName,
                model,
                userId,
                workspace,
                tenantWorkspace,
                lifecycle,
                observedAt,
                evolution,
                memory,
                search,
                List.of());
    }

    public record Evolution(
            int activeSkillCount,
            int draftSkillCount,
            int archivedSkillCount,
            Instant lastEvolvedAt,
            List<Skill> skills) {}

    public record Skill(
            String name,
            String description,
            String state,
            String scope,
            String relativePath,
            long size,
            Instant updatedAt) {}

    public record Memory(
            String summary,
            int dailyFileCount,
            List<String> dailyFiles,
            long conversationCount,
            long sessionLogCount,
            Instant updatedAt) {}

    public record Search(long totalSearches, String lastQuery, Instant lastSearchedAt) {}

    /** 根据当前租户已沉淀的能力、记忆和上次检索生成的下一步研究建议。 */
    public record Recommendation(String id, String title, String prompt, String reason) {}
}
