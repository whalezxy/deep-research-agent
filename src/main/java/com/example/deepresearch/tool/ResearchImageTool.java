package com.example.deepresearch.tool;

import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.extensions.model.dashscope.tool.DashScopeMultiModalTool;
import java.net.URI;
import reactor.core.publisher.Mono;

public class ResearchImageTool {

    private final DashScopeMultiModalTool dashscope;

    public ResearchImageTool(String apiKey) {
        dashscope = new DashScopeMultiModalTool(apiKey);
    }

    @Tool(name = "dashscope_image_to_image", returnDirect = true,
            description = "按用户要求编辑其提供的 HTTPS 图片，返回编辑结果链接。")
    public Mono<ToolResultBlock> edit(
            @ToolParam(name = "image_url", description = "用户提供的公开 HTTPS 图片链接") String imageUrl,
            @ToolParam(name = "prompt", description = "用户指定的图片修改要求") String prompt) {
        if (!isHttps(imageUrl)) {
            return Mono.just(ToolResultBlock.error("请提供可访问的 HTTPS 图片链接"));
        }
        return dashscope.dashscopeImageToImage(imageUrl, prompt, null, null, false)
                .map(this::asLink);
    }

    private ToolResultBlock asLink(ToolResultBlock result) {
        if (result.getState() != ToolResultState.SUCCESS) {
            return result;
        }
        for (var block : result.getOutput()) {
            if (block instanceof ImageBlock image && image.getSource() instanceof URLSource source
                    && isHttps(source.getUrl())) {
                return ToolResultBlock.text("[查看编辑后的图片](<" + source.getUrl() + ">)");
            }
        }
        return ToolResultBlock.error("模型服务未返回可访问的图片链接");
    }

    private boolean isHttps(String value) {
        if (value == null) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
