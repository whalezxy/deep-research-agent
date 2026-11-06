package com.example.deepresearch.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("deep-research.features")
public record ResearchFeatures(
        @DefaultValue("auto") String fallbackModel,
        boolean finalAnswerOnly,
        Duration asyncToolTimeout,
        boolean webTools,
        boolean imageEditing,
        @DefaultValue Sandbox sandbox,
        @DefaultValue Storage storage,
        @DefaultValue Mcp mcp,
        @DefaultValue A2a a2a,
        @DefaultValue Agui agui) {

    public enum Filesystem { LOCAL, DOCKER, KUBERNETES, E2B }

    public enum StateBackend { LOCAL, REDIS, POSTGRESQL, MONGODB }

    public record Sandbox(
            @DefaultValue("local") Filesystem backend,
            String dockerImage,
            @DefaultValue("default") String kubernetesNamespace,
            String kubernetesWarmPool,
            String e2bApiKey,
            String e2bTemplate) {}

    public record Storage(
            @DefaultValue("local") StateBackend backend,
            String redisUri,
            String postgresUrl,
            String postgresUser,
            String postgresPassword,
            String mongodbUri) {}

    public record Mcp(String url, String bearerToken, boolean propagateMetadata, List<String> tools) {}

    public record A2a(String url) {}

    public record Agui(boolean enabled, String bearerToken) {}
}
