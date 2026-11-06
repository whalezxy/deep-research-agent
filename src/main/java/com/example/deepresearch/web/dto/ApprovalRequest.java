package com.example.deepresearch.web.dto;

import com.example.deepresearch.workspace.SessionIdPolicy;
import com.example.deepresearch.workspace.TenantIdPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 浏览器提交的审批请求；不接受客户端传入的 ToolUseBlock，避免篡改待执行参数。 */
public record ApprovalRequest(
        @NotBlank(message = "approvalId 不能为空")
                @Size(max = 100, message = "approvalId 过长")
                String approvalId,
        @NotBlank(message = "userId 不能为空")
                @Pattern(
                        regexp = TenantIdPolicy.REGEX,
                        message = "userId 必须为 1-64 位，只能包含字母、数字、点、下划线和连字符")
                String userId,
        @NotBlank(message = "sessionId 不能为空")
                @Pattern(
                        regexp = SessionIdPolicy.REGEX,
                        message = "sessionId 只能包含字母、数字、点、下划线和连字符")
                String sessionId,
        @NotBlank(message = "decision 不能为空")
                @Pattern(
                        regexp = "(?i)allow|deny",
                        message = "decision 必须为 allow 或 deny")
                String decision) {}
