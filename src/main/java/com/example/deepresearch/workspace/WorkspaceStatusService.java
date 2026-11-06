package com.example.deepresearch.workspace;

import com.example.deepresearch.config.AgentProperties;
import com.example.deepresearch.config.ResearchFeatures;
import com.example.deepresearch.tool.MockTavilySearchTool;
import com.example.deepresearch.tool.MockTavilySearchTool.SearchTelemetry;
import com.example.deepresearch.web.dto.AgentStatusResponse;
import com.example.deepresearch.web.dto.AgentStatusResponse.Evolution;
import com.example.deepresearch.web.dto.AgentStatusResponse.Memory;
import com.example.deepresearch.web.dto.AgentStatusResponse.Recommendation;
import com.example.deepresearch.web.dto.AgentStatusResponse.Search;
import com.example.deepresearch.web.dto.AgentStatusResponse.Skill;
import io.agentscope.core.state.AgentStateStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/** 以只读方式扫描 Workspace，向前端暴露进化状态。 */
@Service
public class WorkspaceStatusService {

    private static final int MEMORY_PREVIEW_LIMIT = 900;

    private final AgentProperties properties;
    private final WorkspaceInitializer workspaceInitializer;
    private final MockTavilySearchTool tavilySearchTool;
    private final AgentStateStore stateStore;
    private final boolean localState;

    public WorkspaceStatusService(
            AgentProperties properties,
            WorkspaceInitializer workspaceInitializer,
            MockTavilySearchTool tavilySearchTool,
            AgentStateStore researchStateStore,
            ResearchFeatures features) {
        this.properties = properties;
        this.workspaceInitializer = workspaceInitializer;
        this.tavilySearchTool = tavilySearchTool;
        this.stateStore = researchStateStore;
        this.localState = features.storage().backend() == ResearchFeatures.StateBackend.LOCAL;
    }

    public AgentStatusResponse snapshot(String requestedUserId) {
        String userId = TenantIdPolicy.requireValid(requestedUserId);
        Path workspace = workspaceInitializer.initialize();
        Path tenantWorkspace = workspaceInitializer.initializeTenant(userId);
        List<Skill> skills = readSkills(workspace, tenantWorkspace);
        Evolution evolution = buildEvolution(skills);
        Memory memory = readMemory(workspace, tenantWorkspace, userId);
        SearchTelemetry telemetry = tavilySearchTool.telemetry(userId);
        Search search =
                new Search(
                        telemetry.invocationCount(), telemetry.lastQuery(), telemetry.lastSearchedAt());

        return new AgentStatusResponse(
                properties.getName(),
                properties.getModel(),
                userId,
                workspace.toString(),
                tenantWorkspace.toString(),
                "READY",
                Instant.now(),
                evolution,
                memory,
                search,
                buildRecommendations(skills, memory, search));
    }

    /** 用于在单轮对话完成后判断 Harness 是否真的写入了长期记忆。 */
    public String memoryVersion(String requestedUserId) {
        String userId = TenantIdPolicy.requireValid(requestedUserId);
        Path tenantWorkspace = workspaceInitializer.initializeTenant(userId);
        Path memoryFile = tenantWorkspace.resolve("MEMORY.md");
        Path dailyMemoryDir = tenantWorkspace.resolve("memory");
        StringBuilder version = new StringBuilder(fileVersion(memoryFile));
        for (String dailyFile : listDailyMemoryFiles(dailyMemoryDir)) {
            version.append('|').append(dailyFile).append(':').append(fileVersion(dailyMemoryDir.resolve(dailyFile)));
        }
        return version.toString();
    }

    /** 返回“版本指纹 -> Skill 名称”，用于检测当前租户本轮新增或更新的能力。 */
    public Map<String, String> activeSkillVersions(String requestedUserId) {
        String userId = TenantIdPolicy.requireValid(requestedUserId);
        Path workspace = workspaceInitializer.initialize();
        Path tenantWorkspace = workspaceInitializer.initializeTenant(userId);
        Map<String, String> versions = new LinkedHashMap<>();
        for (Skill skill : readSkills(workspace, tenantWorkspace)) {
            if ("ACTIVE".equals(skill.state())) {
                String fingerprint =
                        skill.relativePath()
                                + "@"
                                + skill.size()
                                + "@"
                                + (skill.updatedAt() == null ? 0 : skill.updatedAt().toEpochMilli());
                versions.put(fingerprint, skill.name());
            }
        }
        return Map.copyOf(versions);
    }

    private List<Skill> readSkills(Path workspace, Path tenantWorkspace) {
        List<Skill> skills = new ArrayList<>();
        readSkillsFromRoot(workspace, workspace.resolve("skills"), "SHARED", skills);
        readSkillsFromRoot(workspace, tenantWorkspace.resolve("skills"), "TENANT", skills);

        skills.sort(
                Comparator.comparingInt((Skill skill) -> "TENANT".equals(skill.scope()) ? 0 : 1)
                        .thenComparing(
                                Skill::updatedAt,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Skill::name));
        return List.copyOf(skills);
    }

    public record SkillDetail(Skill skill, String content) {}

    /** 只接受当前租户列表中的文件，并验证真实路径以阻止符号链接越界。 */
    public SkillDetail skillDetail(String requestedUserId, String relativePath) {
        String userId = TenantIdPolicy.requireValid(requestedUserId);
        Path workspace = workspaceInitializer.initialize();
        Path tenant = workspaceInitializer.initializeTenant(userId);
        Skill skill = readSkills(workspace, tenant).stream()
                .filter(item -> item.relativePath().equals(relativePath))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("技能不存在或不属于当前租户"));
        try {
            Path root = ("TENANT".equals(skill.scope()) ? tenant : workspace)
                    .resolve("skills").toAbsolutePath().normalize();
            Path file = workspace.resolve(skill.relativePath()).toRealPath();
            if (!root.toRealPath().equals(root) || !file.startsWith(root)
                    || Files.size(file) > 1_048_576) {
                throw new IllegalArgumentException("技能路径不可访问或文件超过 1 MB");
            }
            return new SkillDetail(skill, Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("技能详情读取失败", exception);
        }
    }

    private void readSkillsFromRoot(
            Path workspace, Path skillsRoot, String scope, List<Skill> target) {
        if (Files.notExists(skillsRoot)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(skillsRoot, 5)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> "SKILL.md".equalsIgnoreCase(path.getFileName().toString()))
                    .forEach(path -> target.add(readSkill(workspace, path, scope)));
        } catch (IOException exception) {
            throw new IllegalStateException("读取 Skills 目录失败: " + skillsRoot, exception);
        }
    }

    private Skill readSkill(Path workspace, Path skillFile, String scope) {
        try {
            String content = Files.readString(skillFile, StandardCharsets.UTF_8);
            String relativePath = normalizePath(workspace.relativize(skillFile));
            String state = resolveSkillState(relativePath);
            String fallbackName = skillFile.getParent().getFileName().toString();
            String name = frontMatterValue(content, "name", fallbackName);
            String description =
                    frontMatterValue(content, "description", "Harness 自动沉淀的研究技能");
            return new Skill(
                    name,
                    description,
                    state,
                    scope,
                    relativePath,
                    Files.size(skillFile),
                    Files.getLastModifiedTime(skillFile).toInstant());
        } catch (IOException exception) {
            throw new IllegalStateException("读取 Skill 失败: " + skillFile, exception);
        }
    }

    private Evolution buildEvolution(List<Skill> skills) {
        Set<String> activeNames = new HashSet<>();
        int draft = 0;
        int archived = 0;
        Instant lastEvolvedAt = null;
        for (Skill skill : skills) {
            switch (skill.state()) {
                case "ACTIVE" -> activeNames.add(skill.name());
                case "DRAFT" -> draft++;
                case "ARCHIVED" -> archived++;
                default -> {
                    // 当前只有以上三种状态。
                }
            }
            if (skill.updatedAt() != null
                    && (lastEvolvedAt == null || skill.updatedAt().isAfter(lastEvolvedAt))) {
                lastEvolvedAt = skill.updatedAt();
            }
        }
        return new Evolution(activeNames.size(), draft, archived, lastEvolvedAt, skills);
    }

    /**
     * 推荐逻辑刻意保持确定性：不额外调用模型，也不泄露长期记忆正文；只利用当前租户已有的
     * Skill、是否已有记忆以及最近一次检索主题生成下一步问题。
     */
    private List<Recommendation> buildRecommendations(
            List<Skill> skills, Memory memory, Search search) {
        List<Recommendation> recommendations = new ArrayList<>();
        Map<String, Skill> effectiveSkills = new LinkedHashMap<>();
        skills.stream()
                .filter(skill -> "ACTIVE".equals(skill.state()))
                .sorted(
                        Comparator.comparingInt(
                                        (Skill skill) -> "TENANT".equals(skill.scope()) ? 0 : 1)
                                .thenComparing(Skill::name))
                .forEach(skill -> effectiveSkills.putIfAbsent(skill.name(), skill));
        List<Skill> reusableSkills = effectiveSkills.values().stream().limit(2).toList();

        for (Skill skill : reusableSkills) {
            boolean tenantSkill = "TENANT".equals(skill.scope());
            String scopeLabel = tenantSkill ? "当前租户已沉淀" : "共享 Workspace 已提供";
            recommendations.add(
                    new Recommendation(
                            (tenantSkill ? "reuse-tenant-" : "reuse-shared-") + skill.name(),
                            (tenantSkill ? "复用「" : "使用共享「") + skill.name() + "」继续研究",
                            "请"
                                    + (tenantSkill ? "复用当前租户已沉淀的" : "使用共享的")
                                    + " Skill「"
                                    + skill.name()
                                    + "」，为企业知识库智能体补充一轮研究。"
                                    + "请明确说明复用了哪些步骤，不要创建重复 Skill。",
                            scopeLabel + "的研究方法"));
        }

        if (hasSubstantiveMemory(memory)) {
            recommendations.add(
                    new Recommendation(
                            "memory-next-step",
                            "基于长期记忆规划下一步",
                            "请结合当前租户的长期记忆，为当前项目生成下一阶段研究问题、"
                                    + "验证指标和风险清单；不要引用其他租户信息。",
                            "当前租户已有可持续复用的项目上下文"));
        }

        if (search.lastQuery() != null && !search.lastQuery().isBlank()) {
            recommendations.add(
                    new Recommendation(
                            "challenge-last-search",
                            "补充上次研究的反方证据",
                            "请围绕上次检索主题“"
                                    + abbreviate(search.lastQuery(), 80)
                                    + "”补充反方证据、失败案例和待验证项，"
                                    + "并避免创建同义重复 Skill。",
                            "上次检索已经形成可继续验证的主题"));
        }

        addFallbackRecommendation(
                recommendations,
                "deep-research",
                "发起一次可沉淀能力的深度研究",
                "深度研究：为企业引入 AI Agent 设计技术选型、证据验证和风险治理框架。",
                "完成复杂研究后可观察 Harness 自动沉淀 Skill");
        addFallbackRecommendation(
                recommendations,
                "compare-approaches",
                "比较两种方案的适用边界",
                "调研并比较 RAG 与 Agentic Search 的适用场景、成本、风险和主要评估指标。",
                "适合触发多轮模拟检索与交叉验证");
        addFallbackRecommendation(
                recommendations,
                "memory-check",
                "验证租户长期记忆",
                "请基于当前租户的长期记忆，整理已知项目约束、待验证假设和下一步行动。",
                "用于验证新会话下的记忆恢复能力");

        return List.copyOf(recommendations.subList(0, Math.min(3, recommendations.size())));
    }

    private void addFallbackRecommendation(
            List<Recommendation> recommendations,
            String id,
            String title,
            String prompt,
            String reason) {
        if (recommendations.size() < 3) {
            recommendations.add(new Recommendation(id, title, prompt, reason));
        }
    }

    private boolean hasSubstantiveMemory(Memory memory) {
        if (memory.dailyFileCount() > 0) {
            return true;
        }
        String summary = memory.summary() == null ? "" : memory.summary();
        String meaningfulContent =
                summary.replace("# 长期记忆", "")
                        .replace("> 该租户的记忆由 AgentScope Harness 自动归并维护。", "")
                        .replace("> 该文件由 AgentScope Harness 自动归并维护。", "")
                        .replace("尚未形成长期记忆。完成研究对话后，Harness 会自动抽取并归并关键事实。", "")
                        .strip();
        return !meaningfulContent.isBlank();
    }

    private Memory readMemory(Path workspace, Path tenantWorkspace, String userId) {
        Path memoryFile = tenantWorkspace.resolve("MEMORY.md");
        Path dailyMemoryDir = tenantWorkspace.resolve("memory");
        String summary = readPreview(memoryFile);
        List<String> dailyFiles = listDailyMemoryFiles(dailyMemoryDir);
        long agentStateCount = localState
                ? countNamedFiles(workspace.resolve("memory/conversations").resolve(userId), "agent_state.json")
                : stateStore.listSessionIds(userId).size();
        // 历史回放文件是 UI 层的补充记录；用 max 避免同一 Session 被两层存储重复计数。
        long historyCount = countFilesWithSuffix(tenantWorkspace.resolve("session-history"), ".json");
        long conversationCount = Math.max(agentStateCount, historyCount);
        long sessionLogCount =
                countFilesWithSuffix(tenantWorkspace.resolve("agents"), ".log.jsonl");
        Instant updatedAt = latestModified(memoryFile, dailyMemoryDir, dailyFiles);

        if (summary.isBlank()) {
            summary = "尚未形成长期记忆。完成研究对话后，Harness 会自动抽取并归并关键事实。";
        }

        return new Memory(
                summary,
                dailyFiles.size(),
                dailyFiles,
                conversationCount,
                sessionLogCount,
                updatedAt);
    }

    private List<String> listDailyMemoryFiles(Path memoryDir) {
        if (Files.notExists(memoryDir)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(memoryDir)) {
            return paths.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.matches("\\d{4}-\\d{2}-\\d{2}\\.md"))
                    .sorted(Comparator.reverseOrder())
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("读取长期记忆目录失败: " + memoryDir, exception);
        }
    }

    private long countNamedFiles(Path root, String filename) {
        if (Files.notExists(root)) {
            return 0;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> filename.equals(path.getFileName().toString()))
                    .count();
        } catch (IOException exception) {
            return 0;
        }
    }

    private long countFilesWithSuffix(Path root, String suffix) {
        if (Files.notExists(root)) {
            return 0;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .count();
        } catch (IOException exception) {
            return 0;
        }
    }

    private Instant latestModified(Path memoryFile, Path memoryDir, List<String> dailyFiles) {
        Instant latest = modifiedAt(memoryFile);
        for (String dailyFile : dailyFiles) {
            Instant candidate = modifiedAt(memoryDir.resolve(dailyFile));
            if (candidate != null && (latest == null || candidate.isAfter(latest))) {
                latest = candidate;
            }
        }
        return latest;
    }

    private Instant modifiedAt(Path path) {
        try {
            FileTime lastModified = Files.exists(path) ? Files.getLastModifiedTime(path) : null;
            return lastModified == null ? null : lastModified.toInstant();
        } catch (IOException exception) {
            return null;
        }
    }

    private String fileVersion(Path path) {
        try {
            if (Files.notExists(path)) {
                return "missing";
            }
            return Files.size(path) + "@" + Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            return "unreadable";
        }
    }

    private String readPreview(Path path) {
        if (Files.notExists(path)) {
            return "";
        }
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8).strip();
            if (content.length() <= MEMORY_PREVIEW_LIMIT) {
                return content;
            }
            return content.substring(0, MEMORY_PREVIEW_LIMIT) + "\n…";
        } catch (IOException exception) {
            return "长期记忆暂时不可读。";
        }
    }

    private String resolveSkillState(String relativePath) {
        String normalized = relativePath.toLowerCase(Locale.ROOT);
        if (normalized.contains("/skills/_drafts/") || normalized.startsWith("skills/_drafts/")) {
            return "DRAFT";
        }
        if (normalized.contains("/skills/.archive/")
                || normalized.startsWith("skills/.archive/")) {
            return "ARCHIVED";
        }
        return "ACTIVE";
    }

    private String frontMatterValue(String content, String key, String fallback) {
        String[] lines = content.split("\\R");
        if (lines.length == 0 || !"---".equals(lines[0].strip())) {
            return fallback;
        }

        String prefix = key + ":";
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index].strip();
            if ("---".equals(line)) {
                break;
            }
            if (line.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                String value = line.substring(prefix.length()).strip();
                return stripQuotes(value.isBlank() ? fallback : value);
            }
        }
        return fallback;
    }

    private String stripQuotes(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private String abbreviate(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(1, limit - 1)) + "…";
    }

    private String normalizePath(Path path) {
        return path.toString().replace('\\', '/');
    }
}
