package com.example.deepresearch.web.dto;

import com.example.deepresearch.workspace.SessionIdPolicy;
import com.example.deepresearch.workspace.TenantIdPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        @NotBlank(message = "message 不能为空")
                @Size(max = 12_000, message = "message 不能超过 12000 个字符")
                String message,
        @NotBlank(message = "userId 不能为空")
                @Pattern(
                        regexp = TenantIdPolicy.REGEX,
                        message = "userId 必须为 1-64 位，只能包含字母、数字、点、下划线和连字符")
                String userId,
        @Pattern(
                        regexp = SessionIdPolicy.REGEX,
                        message = "sessionId 只能包含字母、数字、点、下划线和连字符")
                String sessionId) {}
