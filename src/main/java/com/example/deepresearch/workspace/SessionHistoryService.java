package com.example.deepresearch.workspace;

import com.example.deepresearch.config.ResearchFeatures;
import com.example.deepresearch.web.dto.SessionDetailResponse;
import com.example.deepresearch.web.dto.SessionDetailResponse.Message;
import com.example.deepresearch.web.dto.SessionHistoryResponse;
import com.example.deepresearch.web.dto.SessionHistoryResponse.SessionSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.state.AgentStateStore;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/**
 * 保存面向 UI 的会话目录和文本回放。
 *
 * <p>AgentScope 的状态存储负责上下文恢复。本服务单独保存用户可阅读的标题和最终文本。
 */
@Service
public class SessionHistoryService {

    private static final String HISTORY_DIR = "session-history";
    private static final String STATE_DIR = "memory/conversations";
    private static final int TITLE_LIMIT = 54;

    private final WorkspaceInitializer workspaceInitializer;
    private final ObjectMapper objectMapper;
    private final AgentStateStore stateStore;
    private final boolean localState;
    private final ConcurrentMap<String, Object> sessionLocks = new ConcurrentHashMap<>();

    public SessionHistoryService(WorkspaceInitializer workspaceInitializer, ObjectMapper objectMapper,
            AgentStateStore researchStateStore, ResearchFeatures features) {
        this.workspaceInitializer = workspaceInitializer;
        this.objectMapper = objectMapper;
        this.stateStore = researchStateStore;
        this.localState = features.storage().backend() == ResearchFeatures.StateBackend.LOCAL;
    }

    /** 在模型开始执行前记录用户输入，因此中途中断的会话也能被恢复。 */
    public void appendUserMessage(String requestedUserId, String requestedSessionId, String content) {
        appendMessage(requestedUserId, requestedSessionId, "user", content);
    }

    /** 只保存最终回答，不把中间 token 或内部推理写入展示型会话记录。 */
    public void appendAssistantMessage(String requestedUserId, String requestedSessionId, String content) {
        appendMessage(requestedUserId, requestedSessionId, "assistant", content);
    }

    public SessionHistoryResponse listSessions(String requestedUserId) {
        String userId = TenantIdPolicy.requireValid(requestedUserId);
        Path tenantWorkspace = workspaceInitializer.initializeTenant(userId);
        Map<String, SessionSummary> sessions = new HashMap<>();

        Path historyRoot = tenantWorkspace.resolve(HISTORY_DIR);
        if (Files.isDirectory(historyRoot)) {
            try (Stream<Path> paths = Files.list(historyRoot)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".json"))
                        .forEach(
                                path ->
                                        readStored(path)
                                                .ifPresent(
                                                        stored ->
                                                                sessions.put(
                                                                        stored.sessionId(),
                                                                        toSummary(stored, path))));
            } catch (IOException exception) {
                throw new IllegalStateException("读取租户会话目录失败: " + historyRoot, exception);
            }
        }

        if (localState) {
            addLocalStateSessions(userId, sessions);
        } else {
            stateStore.listSessionIds(userId).forEach(id -> addStateSession(sessions, id, null));
        }

        List<SessionSummary> ordered = new ArrayList<>(sessions.values());
        ordered.sort(
                Comparator.comparing(
                                SessionSummary::updatedAt,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(SessionSummary::sessionId));
        return new SessionHistoryResponse(userId, List.copyOf(ordered));
    }

    private void addLocalStateSessions(String userId, Map<String, SessionSummary> sessions) {
        Path stateRoot = workspaceInitializer.initialize().resolve(STATE_DIR).resolve(userId);
        if (!Files.isDirectory(stateRoot)) {
            return;
        }
        try (Stream<Path> paths = Files.list(stateRoot)) {
            paths.filter(Files::isDirectory).forEach(path ->
                    addStateSession(sessions, path.getFileName().toString(), modifiedAt(path)));
        } catch (IOException exception) {
            throw new IllegalStateException("读取 AgentState 会话目录失败 " + stateRoot, exception);
        }
    }

    private void addStateSession(Map<String, SessionSummary> sessions, String id, Instant updatedAt) {
        try {
            String sessionId = SessionIdPolicy.requireValid(id);
            sessions.putIfAbsent(sessionId, new SessionSummary(sessionId,
                    "已持久化会话 " + abbreviate(sessionId, 12), updatedAt, updatedAt, 0));
        } catch (IllegalArgumentException ignored) {
            // 跳过存储中来自旧版或其他应用的非法会话 ID。
        }
    }

    public SessionDetailResponse getSession(String requestedUserId, String requestedSessionId) {
        String userId = TenantIdPolicy.requireValid(requestedUserId);
        String sessionId = SessionIdPolicy.requireValid(requestedSessionId);
        Path tenantWorkspace = workspaceInitializer.initializeTenant(userId);
        Path historyFile = historyFile(tenantWorkspace, sessionId);

        Optional<StoredSession> stored = readStored(historyFile);
        if (stored.isPresent()) {
            StoredSession value = stored.get();
            return new SessionDetailResponse(
                    userId,
                    sessionId,
                    value.title(),
                    value.createdAt(),
                    value.updatedAt(),
                    value.messages().stream()
                            .map(message -> new Message(message.role(), message.content(), message.createdAt()))
                            .toList());
        }

        Path stateDirectory =
                workspaceInitializer.initialize().resolve(STATE_DIR).resolve(userId).resolve(sessionId);
        if (localState ? Files.isDirectory(stateDirectory) : stateStore.exists(userId, sessionId)) {
            Instant updatedAt = localState ? modifiedAt(stateDirectory) : null;
            return new SessionDetailResponse(
                    userId,
                    sessionId,
                    "已持久化会话 " + abbreviate(sessionId, 12),
                    updatedAt,
                    updatedAt,
                    List.of());
        }
        throw new IllegalArgumentException("找不到当前租户的会话: " + sessionId);
    }

    private void appendMessage(
            String requestedUserId, String requestedSessionId, String role, String rawContent) {
        String userId = TenantIdPolicy.requireValid(requestedUserId);
        String sessionId = SessionIdPolicy.requireValid(requestedSessionId);
        String content = rawContent == null ? "" : rawContent.strip();
        if (content.isEmpty()) {
            return;
        }

        Path tenantWorkspace = workspaceInitializer.initializeTenant(userId);
        Path file = historyFile(tenantWorkspace, sessionId);
        Object lock = sessionLocks.computeIfAbsent(userId + ":" + sessionId, ignored -> new Object());
        synchronized (lock) {
            Instant now = Instant.now();
            StoredSession existing =
                    readStored(file)
                            .orElse(
                                    new StoredSession(
                                            sessionId,
                                            "assistant".equals(role)
                                                    ? "会话 " + abbreviate(sessionId, 12)
                                                    : titleFrom(content),
                                            now,
                                            now,
                                            List.of()));
            List<StoredMessage> messages = new ArrayList<>(existing.messages());
            messages.add(new StoredMessage(role, content, now));
            String title = existing.title();
            if ((title == null || title.isBlank() || title.startsWith("会话 ")) && "user".equals(role)) {
                title = titleFrom(content);
            }
            writeStored(
                    file,
                    new StoredSession(
                            sessionId,
                            title,
                            existing.createdAt() == null ? now : existing.createdAt(),
                            now,
                            List.copyOf(messages)));
        }
    }

    private Path historyFile(Path tenantWorkspace, String sessionId) {
        return tenantWorkspace.resolve(HISTORY_DIR).resolve(sessionId + ".json");
    }

    private Optional<StoredSession> readStored(Path file) {
        if (Files.notExists(file)) {
            return Optional.empty();
        }
        try {
            StoredSession session = objectMapper.readValue(file.toFile(), StoredSession.class);
            if (session == null || session.sessionId() == null || session.sessionId().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(normalizeStored(session));
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    private StoredSession normalizeStored(StoredSession session) {
        Instant now = Instant.now();
        String sessionId = SessionIdPolicy.requireValid(session.sessionId());
        List<StoredMessage> messages = session.messages() == null ? List.of() : List.copyOf(session.messages());
        return new StoredSession(
                sessionId,
                session.title() == null || session.title().isBlank()
                        ? "会话 " + abbreviate(sessionId, 12)
                        : session.title(),
                session.createdAt() == null ? now : session.createdAt(),
                session.updatedAt() == null ? now : session.updatedAt(),
                messages);
    }

    private void writeStored(Path file, StoredSession session) {
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), session);
            try {
                Files.move(
                        temporary,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("写入会话历史失败: " + file, exception);
        }
    }

    private SessionSummary toSummary(StoredSession session, Path file) {
        return new SessionSummary(
                session.sessionId(),
                session.title(),
                session.createdAt(),
                session.updatedAt() == null ? modifiedAt(file) : session.updatedAt(),
                session.messages().size());
    }

    private Instant modifiedAt(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException exception) {
            return null;
        }
    }

    private String titleFrom(String content) {
        return abbreviate(content.replaceAll("\\s+", " ").strip(), TITLE_LIMIT);
    }

    private String abbreviate(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(1, limit - 1)) + "…";
    }

    private record StoredSession(
            String sessionId,
            String title,
            Instant createdAt,
            Instant updatedAt,
            List<StoredMessage> messages) {}

    private record StoredMessage(String role, String content, Instant createdAt) {}
}
