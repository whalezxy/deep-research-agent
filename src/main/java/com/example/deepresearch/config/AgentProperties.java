package com.example.deepresearch.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 深度研究智能体的外部化配置。 */
@Validated
@ConfigurationProperties(prefix = "deep-research.agent")
public class AgentProperties {

    @NotBlank private String name = "deep-research-agent";

    @NotBlank private String model = "dashscope:qwen-plus";

    private String dashscopeApiKey = "";

    @NotNull private Path workspace = Path.of("./workspace");

    @Min(1) private int maxIterations = 16;

    @Min(2) private int compactionTriggerMessages = 18;

    @Min(1) private int compactionKeepMessages = 6;

    @NotNull private Duration callTimeout = Duration.ofMinutes(5);

    private boolean skillAutoPromote = true;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getDashscopeApiKey() {
        return dashscopeApiKey;
    }

    public void setDashscopeApiKey(String dashscopeApiKey) {
        this.dashscopeApiKey = dashscopeApiKey;
    }

    public Path getWorkspace() {
        return workspace;
    }

    public void setWorkspace(Path workspace) {
        this.workspace = workspace;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }

    public int getCompactionTriggerMessages() {
        return compactionTriggerMessages;
    }

    public void setCompactionTriggerMessages(int compactionTriggerMessages) {
        this.compactionTriggerMessages = compactionTriggerMessages;
    }

    public int getCompactionKeepMessages() {
        return compactionKeepMessages;
    }

    public void setCompactionKeepMessages(int compactionKeepMessages) {
        this.compactionKeepMessages = compactionKeepMessages;
    }

    public Duration getCallTimeout() {
        return callTimeout;
    }

    public void setCallTimeout(Duration callTimeout) {
        this.callTimeout = callTimeout;
    }

    public boolean isSkillAutoPromote() {
        return skillAutoPromote;
    }

    public void setSkillAutoPromote(boolean skillAutoPromote) {
        this.skillAutoPromote = skillAutoPromote;
    }
}
