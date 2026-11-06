package com.example.deepresearch.web;

import com.example.deepresearch.config.AgentProperties;
import com.example.deepresearch.config.ResearchFeatures;
import com.example.deepresearch.tool.MockTavilySearchTool;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/** 为 AG-UI 客户端提供独立的只读研究会话。 */
@RestController
@ConditionalOnProperty(prefix = "deep-research.features.agui", name = "enabled", havingValue = "true")
public class AguiResearchController {

    private final Model model;
    private final byte[] authorization;
    private final AguiAdapterConfig adapterConfig;
    private final MockTavilySearchTool searchTool = new MockTavilySearchTool();

    public AguiResearchController(Model researchModel, ResearchFeatures features, AgentProperties properties) {
        String token = features.agui().bearerToken();
        if (!StringUtils.hasText(token)) {
            throw new IllegalArgumentException("启用 AG-UI 时请配置 deep-research.features.agui.bearer-token");
        }
        model = researchModel;
        authorization = ("Bearer " + token.strip()).getBytes(StandardCharsets.UTF_8);
        adapterConfig = AguiAdapterConfig.builder()
                .emitTokenUsage(true)
                .runTimeout(properties.getCallTimeout())
                .build();
    }

    @PostMapping(path = "/api/agui/run", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<AguiEvent>>> run(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody RunAgentInput input) {
        if (auth == null || !MessageDigest.isEqual(authorization, auth.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!StringUtils.hasText(input.getThreadId()) || !StringUtils.hasText(input.getRunId())) {
            throw new IllegalArgumentException("AG-UI 请求需要 threadId 和 runId");
        }
        Flux<ServerSentEvent<AguiEvent>> events = Flux.using(
                this::newAgent,
                agent -> new AguiAgentAdapter(agent, adapterConfig).run(input)
                        .map(event -> ServerSentEvent.builder(event).build()),
                ReActAgent::close);
        return ResponseEntity.ok(events);
    }

    private ReActAgent newAgent() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(searchTool);
        return ReActAgent.builder()
                .name("agui-research")
                .model(model)
                .toolkit(toolkit)
                .sysPrompt("用中文回答研究问题。tavily_search 的结果是离线模拟资料，引用时明确注明。")
                .maxIters(8)
                .build();
    }
}
