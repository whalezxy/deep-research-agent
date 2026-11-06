package com.example.deepresearch.workspace;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 统一校验租户 ID，防止路径穿越以及与 Workspace 保留目录发生冲突。 */
public final class TenantIdPolicy {

    public static final String REGEX = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}";

    private static final Pattern PATTERN = Pattern.compile(REGEX);
    private static final Pattern WINDOWS_DEVICE_NAME =
            Pattern.compile("^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?$");
    private static final Set<String> RESERVED_NAMES =
            Set.of(
                    "agents",
                    "knowledge",
                    "memory",
                    "plans",
                    "skills",
                    "subagents",
                    "tasks");

    private TenantIdPolicy() {}

    public static String requireValid(String userId) {
        // 统一为小写，避免 Windows/macOS 默认大小写不敏感文件系统中的目录碰撞。
        String normalized = userId == null ? "" : userId.strip().toLowerCase(Locale.ROOT);
        if (!PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "userId 必须为 1-64 位，只能包含字母、数字、点、下划线和连字符");
        }
        if (normalized.endsWith(".")) {
            throw new IllegalArgumentException("userId 不能以点结尾");
        }
        if (RESERVED_NAMES.contains(normalized.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("userId 不能使用 Workspace 保留名称: " + normalized);
        }
        if (WINDOWS_DEVICE_NAME.matcher(normalized).matches()) {
            throw new IllegalArgumentException("userId 不能使用系统保留设备名: " + normalized);
        }
        return normalized;
    }
}
