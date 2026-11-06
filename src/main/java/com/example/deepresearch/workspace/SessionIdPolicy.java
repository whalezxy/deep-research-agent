package com.example.deepresearch.workspace;

import java.util.Locale;
import java.util.regex.Pattern;

/** 校验会话 ID，确保其可安全作为租户目录下的持久化文件名。 */
public final class SessionIdPolicy {

    public static final String REGEX = "[A-Za-z0-9][A-Za-z0-9._-]{0,99}";

    private static final Pattern PATTERN = Pattern.compile(REGEX);
    private static final Pattern WINDOWS_DEVICE_NAME =
            Pattern.compile("^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?$");

    private SessionIdPolicy() {}

    public static String requireValid(String sessionId) {
        // 与租户 ID 一样规范成小写，避免大小写不敏感文件系统发生目录碰撞。
        String normalized = sessionId == null ? "" : sessionId.strip().toLowerCase(Locale.ROOT);
        if (!PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "sessionId 必须为 1-100 位，只能包含字母、数字、点、下划线和连字符");
        }
        if (normalized.endsWith(".")) {
            throw new IllegalArgumentException("sessionId 不能以点结尾");
        }
        if (WINDOWS_DEVICE_NAME.matcher(normalized).matches()) {
            throw new IllegalArgumentException("sessionId 不能使用系统保留设备名: " + normalized);
        }
        return normalized;
    }
}
