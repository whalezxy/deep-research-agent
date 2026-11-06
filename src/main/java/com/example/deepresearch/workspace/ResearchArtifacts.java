package com.example.deepresearch.workspace;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryRequest;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryResult;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryTarget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class ResearchArtifacts implements ArtifactDeliveryTarget {

    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final String FILE_NAME = "[A-Za-z0-9][A-Za-z0-9._-]{0,75}\\.(?:md|csv)";
    private static final Pattern ACCEPTED_NAME = Pattern.compile(FILE_NAME, Pattern.CASE_INSENSITIVE);
    private static final Pattern DOWNLOAD_ID = Pattern.compile("[0-9a-f]{32}-(" + FILE_NAME + ")",
            Pattern.CASE_INSENSITIVE);

    private final WorkspaceInitializer workspaceInitializer;

    public ResearchArtifacts(WorkspaceInitializer workspaceInitializer) {
        this.workspaceInitializer = workspaceInitializer;
    }

    @Override
    public ArtifactDeliveryResult deliver(RuntimeContext context, ArtifactDeliveryRequest request) {
        if (context == null || context.getUserId() == null) {
            return ArtifactDeliveryResult.fail("交付文件需要有效的租户身份");
        }
        String filename = request.fileName();
        if (!ACCEPTED_NAME.matcher(filename).matches()) {
            return ArtifactDeliveryResult.fail("请使用由英文字母、数字、点、下划线或连字符组成的 .md 或 .csv 文件名");
        }
        if (request.content().length > MAX_BYTES) {
            return ArtifactDeliveryResult.fail("研究文件最多 5 MiB");
        }

        String userId = TenantIdPolicy.requireValid(context.getUserId());
        String id = UUID.randomUUID().toString().replace("-", "") + "-" + filename;
        Path directory = artifactDirectory(userId);
        try {
            Files.createDirectories(directory);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                return ArtifactDeliveryResult.fail("交付目录不可用");
            }
            Files.write(directory.resolve(id), request.content(), StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            String link = UriComponentsBuilder.fromPath("/api/agent/artifacts/{id}")
                    .queryParam("userId", userId).buildAndExpand(id).toUriString();
            return ArtifactDeliveryResult.success("已交付 " + filename + "。下载地址 " + link);
        } catch (IOException exception) {
            return ArtifactDeliveryResult.fail("保存研究文件失败");
        }
    }

    public Optional<Download> download(String userId, String id) {
        Matcher matcher = DOWNLOAD_ID.matcher(id);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        Path directory = artifactDirectory(userId);
        Path file = directory.resolve(id);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        return Optional.of(new Download(matcher.group(1), new FileSystemResource(file)));
    }

    private Path artifactDirectory(String userId) {
        return workspaceInitializer.initializeTenant(userId).resolve("artifacts");
    }

    public record Download(String filename, Resource resource) {}
}
