package com.example.deepresearch.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.state.AgentState;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * 模拟 Tavily 搜索工具。
 *
 * <p>它不会访问互联网，而是返回结构化的多视角结果，便于离线演示 ReAct 多轮检索与 Skill 沉淀流程。
 */
@Component
public class MockTavilySearchTool {

    private static final List<ResultTemplate> RESULT_TEMPLATES =
            List.of(
                    new ResultTemplate(
                            "权威概览与术语边界",
                            "给出核心定义、适用边界和可核验的一手资料线索，适合作为研究基线。"),
                    new ResultTemplate(
                            "实证数据与对比研究",
                            "汇总可用于验证结论的指标、对照组和时间维度，并提示检查样本口径。"),
                    new ResultTemplate(
                            "反方证据与风险审视",
                            "从失败案例、限制条件和潜在偏差出发，帮助避免只收集支持性证据。"),
                    new ResultTemplate(
                            "行业案例与落地观察",
                            "整理实施路径、组织成本和效果衡量方式，强调案例不能直接替代普遍证据。"),
                    new ResultTemplate(
                            "研究方法与后续验证清单",
                            "提供复核步骤、待补数据和下一轮检索建议，便于形成可重复的研究流程。"));

    private final ConcurrentMap<String, SearchTelemetry> tenantTelemetry =
            new ConcurrentHashMap<>();

    @Tool(
            name = "tavily_search",
            description =
                    "使用 Tavily 风格接口搜索资料，返回标题、URL、相关度与摘要。"
                            + "当前为离线模拟实现，适合通过多组查询演示深度研究流程。",
            readOnly = true,
            stateInjected = true)
    public String search(
            @ToolParam(name = "query", description = "具体且可检索的问题或关键词") String query,
            @ToolParam(
                            name = "max_results",
                            description = "返回结果数，范围 1 到 5，默认 4",
                            required = false)
                    Integer maxResults,
            @ToolParam(
                            name = "search_depth",
                            description = "搜索深度：basic 或 advanced，默认 advanced",
                            required = false)
                    String searchDepth,
            AgentState agentState) {

        String normalizedQuery = query == null ? "" : query.strip();
        if (normalizedQuery.isEmpty()) {
            return "Tavily Mock Error: query 不能为空。";
        }

        int limit = maxResults == null ? 4 : Math.max(1, Math.min(maxResults, 5));
        String depth = normalizeDepth(searchDepth);
        Instant searchedAt = Instant.now();
        String userId = resolveUserId(agentState);
        tenantTelemetry.compute(
                userId,
                (ignored, previous) ->
                        new SearchTelemetry(
                                previous == null ? 1 : previous.invocationCount() + 1,
                                normalizedQuery,
                                searchedAt));

        String slug = toSlug(normalizedQuery);
        StringBuilder result =
                new StringBuilder()
                        .append("# Tavily Mock Search\n\n")
                        .append("> 演示提示：以下内容为离线模拟搜索结果，不代表真实联网检索。\n\n")
                        .append("- query: ")
                        .append(normalizedQuery)
                        .append("\n- search_depth: ")
                        .append(depth)
                        .append("\n- result_count: ")
                        .append(limit)
                        .append("\n\n");

        for (int index = 0; index < limit; index++) {
            ResultTemplate template = RESULT_TEMPLATES.get(index);
            double score = Math.max(0.60, 0.96 - (index * 0.07));
            result.append("## ")
                    .append(index + 1)
                    .append(". ")
                    .append(normalizedQuery)
                    .append("：")
                    .append(template.title())
                    .append("\n")
                    .append("URL: https://demo.tavily.local/")
                    .append(slug)
                    .append("/source-")
                    .append(index + 1)
                    .append("\n")
                    .append("Score: ")
                    .append(String.format(Locale.ROOT, "%.2f", score))
                    .append("\n")
                    .append("摘要：围绕“")
                    .append(normalizedQuery)
                    .append("”，")
                    .append(template.summary())
                    .append("\n\n");
        }

        return result.toString().strip();
    }

    public SearchTelemetry telemetry(String userId) {
        return tenantTelemetry.getOrDefault(userId, new SearchTelemetry(0, null, null));
    }

    private String resolveUserId(AgentState agentState) {
        if (agentState == null || agentState.getUserId() == null || agentState.getUserId().isBlank()) {
            return "__anonymous__";
        }
        return agentState.getUserId();
    }

    private String normalizeDepth(String searchDepth) {
        return "basic".equalsIgnoreCase(searchDepth) ? "basic" : "advanced";
    }

    private String toSlug(String value) {
        String slug =
                value.toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9\\p{IsHan}]+", "-")
                        .replaceAll("^-|-$", "");
        if (slug.isBlank()) {
            slug = "research-query";
        }
        return slug.length() > 48 ? slug.substring(0, 48) : slug;
    }

    private record ResultTemplate(String title, String summary) {}

    public record SearchTelemetry(long invocationCount, String lastQuery, Instant lastSearchedAt) {}
}
