package com.example.deepresearch.agent;

import com.example.deepresearch.workspace.SessionIdPolicy;
import com.example.deepresearch.workspace.TenantIdPolicy;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Service;

/**
 * 保存等待人工确认的工具调用。
 *
 * <p>审批 ID 只作为不透明句柄返回给浏览器；真正的 {@link ToolUseBlock} 永远从服务端取回，
 * 客户端不能伪造工具参数。租户和会话在领取审批时再次校验，避免跨租户批准。
 */
@Service
public class ApprovalRequestService {

    private static final Duration REQUEST_TTL = Duration.ofMinutes(10);

    private final ConcurrentMap<String, PendingApproval> requests = new ConcurrentHashMap<>();

    public PendingApproval register(
            String requestedUserId,
            String requestedSessionId,
            List<ToolUseBlock> toolCalls,
            Map<String, String> skillsBefore,
            String memoryBefore) {
        String userId = TenantIdPolicy.requireValid(requestedUserId);
        String sessionId = SessionIdPolicy.requireValid(requestedSessionId);
        List<ToolUseBlock> normalizedCalls =
                toolCalls == null ? List.of() : toolCalls.stream().filter(call -> call != null).toList();
        if (normalizedCalls.isEmpty()) {
            throw new IllegalArgumentException("没有可供确认的工具调用");
        }

        purgeExpired();
        Instant createdAt = Instant.now();
        PendingApproval pending =
                new PendingApproval(
                        UUID.randomUUID().toString(),
                        userId,
                        sessionId,
                        normalizedCalls,
                        skillsBefore == null ? Map.of() : Map.copyOf(skillsBefore),
                        memoryBefore == null ? "" : memoryBefore,
                        createdAt,
                        createdAt.plus(REQUEST_TTL));
        requests.put(pending.approvalId(), pending);
        return pending;
    }

    /** 校验审批句柄及归属，由会话运行接收确认结果后再移除记录。 */
    public ResolvedApproval resolve(
            String requestedApprovalId,
            String requestedUserId,
            String requestedSessionId,
            String requestedDecision) {
        String approvalId = normalizeApprovalId(requestedApprovalId);
        String userId = TenantIdPolicy.requireValid(requestedUserId);
        String sessionId = SessionIdPolicy.requireValid(requestedSessionId);
        Decision decision = Decision.from(requestedDecision);

        PendingApproval pending = requests.get(approvalId);
        if (pending == null) {
            throw new IllegalArgumentException("审批请求不存在、已处理或已过期");
        }
        if (!pending.userId().equals(userId) || !pending.sessionId().equals(sessionId)) {
            throw new IllegalArgumentException("审批请求不属于当前租户或会话");
        }
        if (pending.expiresAt().isBefore(Instant.now())) {
            requests.remove(approvalId, pending);
            throw new IllegalArgumentException("审批请求已过期，请重新发起研究");
        }
        return new ResolvedApproval(pending, decision);
    }

    public void consume(PendingApproval pending) {
        requests.remove(pending.approvalId(), pending);
    }

    public int pendingCount(String requestedUserId) {
        String userId = TenantIdPolicy.requireValid(requestedUserId);
        purgeExpired();
        return (int) requests.values().stream().filter(request -> request.userId().equals(userId)).count();
    }

    /** 刷新页面时按租户及会话恢复仍有效的审批，不延长有效期。 */
    public List<PendingApproval> pendingFor(String requestedUserId, String requestedSessionId) {
        String userId = TenantIdPolicy.requireValid(requestedUserId);
        String sessionId = SessionIdPolicy.requireValid(requestedSessionId);
        purgeExpired();
        return requests.values().stream()
                .filter(item -> item.userId().equals(userId) && item.sessionId().equals(sessionId))
                .sorted(java.util.Comparator.comparing(PendingApproval::createdAt))
                .toList();
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        requests.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private String normalizeApprovalId(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.length() > 100 || normalized.isBlank()) {
            throw new IllegalArgumentException("approvalId 不合法");
        }
        return normalized;
    }

    public enum Decision {
        ALLOW,
        DENY;

        static Decision from(String value) {
            if (value == null) {
                throw new IllegalArgumentException("decision 必须为 allow 或 deny");
            }
            try {
                return valueOf(value.strip().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("decision 必须为 allow 或 deny", exception);
            }
        }
    }

    public record PendingApproval(
            String approvalId,
            String userId,
            String sessionId,
            List<ToolUseBlock> toolCalls,
            Map<String, String> skillsBefore,
            String memoryBefore,
            Instant createdAt,
            Instant expiresAt) {}

    public record ResolvedApproval(PendingApproval pending, Decision decision) {}
}
