package com.example.deepresearch.web;

import com.example.deepresearch.agent.DeepResearchAgentService;
import com.example.deepresearch.config.AgentProperties;
import com.example.deepresearch.web.dto.AgentStatusResponse;
import com.example.deepresearch.web.dto.ApprovalRequest;
import com.example.deepresearch.web.dto.ChatRequest;
import com.example.deepresearch.web.dto.ChatStreamEvent;
import com.example.deepresearch.web.dto.SessionDetailResponse;
import com.example.deepresearch.web.dto.SessionHistoryResponse;
import com.example.deepresearch.workspace.ResearchArtifacts;
import com.example.deepresearch.workspace.SessionHistoryService;
import com.example.deepresearch.workspace.WorkspaceStatusService;
import jakarta.validation.Valid;
import java.io.IOException;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api")
public class AgentController {

    private final DeepResearchAgentService agentService;
    private final WorkspaceStatusService workspaceStatusService;
    private final AgentProperties properties;
    private final SessionHistoryService sessionHistoryService;
    private final ResearchArtifacts artifacts;

    public AgentController(
            DeepResearchAgentService agentService,
            WorkspaceStatusService workspaceStatusService,
            AgentProperties properties,
            SessionHistoryService sessionHistoryService,
            ResearchArtifacts artifacts) {
        this.agentService = agentService;
        this.workspaceStatusService = workspaceStatusService;
        this.properties = properties;
        this.sessionHistoryService = sessionHistoryService;
        this.artifacts = artifacts;
    }

    /**
     * SSE 聊天接口；同一 userId 下的相同 sessionId 会恢复此前的 Harness 对话状态。
     *
     * <p>浏览器会依次接收 started、delta、tool、ask、complete 或 error 事件。ask 表示本轮暂停，
     * 需要调用 approval 接口提交 allow 或 deny。
     */
    @PostMapping(
            path = "/chat",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody ChatRequest request) {
        return streamResponse(
                agentService.streamChat(request.message(), request.sessionId(), request.userId()),
                request.userId(), request.sessionId(), "SSE 连接异常：");
    }

    /**
     * 接收 ask 事件对应的人工决定，并以新的 SSE 流返回恢复后的研究过程。
     * 客户端只需要提交 approvalId、租户、会话和 allow/deny，不可提交或修改工具参数。
     */
    @PostMapping(
            path = "/agent/approval",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter approval(@Valid @RequestBody ApprovalRequest request) {
        return streamResponse(
                agentService.resumeApproval(
                        request.approvalId(), request.userId(), request.sessionId(), request.decision()),
                request.userId(), request.sessionId(), "审批恢复异常：");
    }

    private SseEmitter streamResponse(
            Flux<ChatStreamEvent> events, String userId, String sessionId, String errorPrefix) {
        SseEmitter emitter =
                new SseEmitter(properties.getCallTimeout().plusSeconds(15).toMillis());
        BaseSubscriber<ChatStreamEvent> subscriber = new BaseSubscriber<>() {
            @Override
            protected void hookOnNext(ChatStreamEvent event) {
                try {
                    emitter.send(SseEmitter.event().name(event.type()).data(event));
                } catch (IOException exception) {
                    cancel();
                } catch (IllegalStateException exception) {
                    cancel();
                    emitter.completeWithError(exception);
                }
            }

            @Override
            protected void hookOnError(Throwable error) {
                emitter.completeWithError(error);
            }

            @Override
            protected void hookOnComplete() {
                emitter.complete();
            }
        };

        emitter.onCompletion(subscriber::cancel);
        emitter.onError(error -> subscriber.cancel());
        emitter.onTimeout(() -> {
            subscriber.cancel();
            emitter.complete();
        });
        events.onErrorResume(error -> Flux.just(
                        ChatStreamEvent.error(userId, sessionId, errorPrefix + safeMessage(error))))
                .subscribe(subscriber);
        return emitter;
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? "未知错误" : message;
    }

    /** 返回指定租户 Workspace 中 Skills、Memory、搜索统计和动态推荐的实时快照。 */
    @GetMapping(path = "/agent/skills/detail", produces = MediaType.APPLICATION_JSON_VALUE)
    public WorkspaceStatusService.SkillDetail skillDetail(
            @RequestParam String userId, @RequestParam String relativePath) {
        return workspaceStatusService.skillDetail(userId, relativePath);
    }

    @GetMapping(path = "/agent/approvals", produces = MediaType.APPLICATION_JSON_VALUE)
    public java.util.List<ChatStreamEvent> pendingApprovals(
            @RequestParam String userId, @RequestParam String sessionId) {
        return agentService.pendingApprovals(userId, sessionId);
    }

    @GetMapping(path = "/agent/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public AgentStatusResponse status(@RequestParam String userId) {
        return workspaceStatusService.snapshot(userId);
    }

    @GetMapping(path = "/agent/artifacts/{artifactId}")
    public ResponseEntity<Resource> artifact(@PathVariable String artifactId, @RequestParam String userId) {
        return artifacts.download(userId, artifactId)
                .map(download -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                ContentDisposition.attachment().filename(download.filename()).build().toString())
                        .body(download.resource()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 列出当前租户可恢复的会话；文本回放记录与 AgentScope 的 AgentState 分开存储。 */
    @GetMapping(path = "/agent/sessions", produces = MediaType.APPLICATION_JSON_VALUE)
    public SessionHistoryResponse sessions(@RequestParam String userId) {
        return sessionHistoryService.listSessions(userId);
    }

    /** 获取一段会话的 UI 回放文本。旧 AgentState 会话会返回空 messages，但仍可继续对话。 */
    @GetMapping(path = "/agent/sessions/{sessionId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public SessionDetailResponse session(
            @RequestParam String userId, @PathVariable String sessionId) {
        return sessionHistoryService.getSession(userId, sessionId);
    }
}
