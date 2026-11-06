package com.example.deepresearch.config;

import com.example.deepresearch.tool.MockTavilySearchTool;
import com.example.deepresearch.tool.ResearchImageTool;
import com.example.deepresearch.tool.ResearchStatusTool;
import com.example.deepresearch.workspace.ResearchArtifacts;
import com.example.deepresearch.workspace.WorkspaceInitializer;
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.a2a.agent.card.WellKnownAgentCardResolver;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.extensions.mongodb.MongoDistributedStore;
import io.agentscope.extensions.postgresql.PostgresDistributedStore;
import io.agentscope.extensions.redis.RedisDistributedStore;
import io.agentscope.extensions.sandbox.e2b.E2bFilesystemSpec;
import io.agentscope.extensions.sandbox.kubernetes.KubernetesFilesystemSpec;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.time.Duration;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import redis.clients.jedis.JedisPooled;

@Configuration(proxyBeanMethods = false)
public class ResearchIntegrations {

    private HarnessAgent sandboxWorker;

    @Bean(destroyMethod = "closeMcpClients")
    public Toolkit researchToolkit(MockTavilySearchTool searchTool, ResearchStatusTool statusTool,
            AgentProperties properties, ResearchFeatures features) {
        Toolkit toolkit = AgentConfiguration.mockSearchToolkit(searchTool);
        toolkit.registerTool(statusTool);
        if (features.imageEditing()) {
            String apiKey = properties.getDashscopeApiKey();
            if (!StringUtils.hasText(apiKey)) {
                throw new IllegalArgumentException("启用图片编辑时请配置 DashScope API Key");
            }
            toolkit.registerTool(new ResearchImageTool(apiKey.strip()));
        }
        ResearchFeatures.Mcp mcp = features.mcp();
        if (!StringUtils.hasText(mcp.url())) {
            return toolkit;
        }

        McpClientBuilder clientBuilder = McpClientBuilder.create("research-mcp")
                .streamableHttpTransport(mcp.url().strip())
                .initializationTimeout(Duration.ofSeconds(15))
                .propagateMeta(mcp.propagateMetadata());
        if (StringUtils.hasText(mcp.bearerToken())) {
            clientBuilder.header("Authorization", "Bearer " + mcp.bearerToken().strip());
        }
        McpClientWrapper client = clientBuilder.buildSync();
        try {
            Toolkit.ToolRegistration registration = toolkit.registration()
                    .mcpClient(client)
                    .mcpToolNamePrefix("mcp_")
                    .propagateMeta(mcp.propagateMetadata());
            if (mcp.tools() != null && !mcp.tools().isEmpty()) {
                registration.enableTools(mcp.tools());
            }
            registration.apply();
        } catch (RuntimeException exception) {
            client.close();
            throw exception;
        }
        return toolkit;
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "deep-research.features.storage", name = "backend", havingValue = "redis")
    public JedisPooled researchRedis(ResearchFeatures features) {
        return new JedisPooled(required(features.storage().redisUri(), "storage.redis-uri"));
    }

    @Bean
    @ConditionalOnProperty(prefix = "deep-research.features.storage", name = "backend", havingValue = "redis")
    public RedisDistributedStore redisStore(JedisPooled researchRedis) {
        return RedisDistributedStore.fromJedis(researchRedis, "deep-research:");
    }

    @Bean
    @ConditionalOnProperty(prefix = "deep-research.features.storage", name = "backend", havingValue = "postgresql")
    public PostgresDistributedStore postgresStore(ResearchFeatures features) {
        ResearchFeatures.Storage storage = features.storage();
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(required(storage.postgresUrl(), "storage.postgres-url"));
        if (StringUtils.hasText(storage.postgresUser())) {
            dataSource.setUser(storage.postgresUser());
        }
        if (StringUtils.hasLength(storage.postgresPassword())) {
            dataSource.setPassword(storage.postgresPassword());
        }
        return PostgresDistributedStore.create(dataSource);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "deep-research.features.storage", name = "backend", havingValue = "mongodb")
    public MongoDistributedStore mongoStore(ResearchFeatures features) {
        return MongoDistributedStore.fromConnectionString(
                required(features.storage().mongodbUri(), "storage.mongodb-uri"));
    }

    @Bean
    public AgentStateStore researchStateStore(
            WorkspaceInitializer workspaceInitializer, ObjectProvider<DistributedStore> stores) {
        DistributedStore distributed = stores.getIfAvailable();
        return distributed != null ? distributed.agentStateStore()
                : new JsonFileAgentStateStore(workspaceInitializer.initialize().resolve("memory/conversations"));
    }

    void configureOptionalWorkers(HarnessAgent.Builder builder, ResearchFeatures features,
            Path workspace, Model model, ResearchArtifacts artifacts) {
        if (StringUtils.hasText(features.a2a().url())) {
            WellKnownAgentCardResolver resolver = WellKnownAgentCardResolver.builder()
                    .baseUrl(features.a2a().url().strip()).build();
            builder.subagentFactory("remote-reviewer", "用户要求时，通过 A2A 向远程智能体委派独立评审",
                    name -> A2aAgent.builder().name(name).agentCardResolver(resolver).build());
        }

        ResearchFeatures.Sandbox sandbox = features.sandbox();
        if (sandbox.backend() == ResearchFeatures.Filesystem.LOCAL) {
            return;
        }
        SandboxFilesystemSpec filesystem = switch (sandbox.backend()) {
            case DOCKER -> new DockerFilesystemSpec()
                    .image(required(sandbox.dockerImage(), "sandbox.docker-image"));
            case KUBERNETES -> new KubernetesFilesystemSpec()
                    .namespace(sandbox.kubernetesNamespace())
                    .warmPoolName(required(sandbox.kubernetesWarmPool(), "sandbox.kubernetes-warm-pool"));
            case E2B -> new E2bFilesystemSpec()
                    .apiKey(required(sandbox.e2bApiKey(), "sandbox.e2b-api-key"))
                    .templateId(required(sandbox.e2bTemplate(), "sandbox.e2b-template"));
            case LOCAL -> throw new IllegalStateException("本地模式无需启动沙箱子智能体");
        };
        sandboxWorker = HarnessAgent.builder()
                .name("sandbox-analyst")
                .sysPrompt("分析主智能体提供的资料。用户要求文件时，在沙箱生成 Markdown 或 CSV，并调用 deliver_artifact 交付。")
                .model(model)
                .workspace(workspace.resolve(".sandbox-analysis"))
                .filesystem(filesystem.isolationScope(IsolationScope.SESSION))
                .stateStore(new InMemoryAgentStateStore())
                .artifactDeliveryTarget(artifacts)
                .disableSubagents()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableWebTools()
                .maxIters(8)
                .build();
        builder.subagentFactory("sandbox-analyst", "按用户要求在沙箱分析数据并交付 Markdown 或 CSV",
                name -> sandboxWorker);
    }

    @PreDestroy
    void closeSandboxWorker() {
        if (sandboxWorker != null) {
            sandboxWorker.close();
        }
    }

    private static String required(String value, String property) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("请配置 deep-research.features." + property);
        }
        return value.strip();
    }
}
