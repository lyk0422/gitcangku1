package com.example.starter.domain;

import java.util.regex.Pattern;

/**
 * 内容校验摘要格式策略：统一使用 {@code sha256:<64 位小写十六进制>}。
 */
public final class ChecksumPolicy {

    /** 要求的有效见证角色数：两名不同发布角色。 */
    public static final int REQUIRED_WITNESSES = 2;

    private static final String PREFIX = "sha256:";
    private static final Pattern LOWER_HEX_64 = Pattern.compile("[0-9a-f]{64}");

    private ChecksumPolicy() {
    }

    /** 摘要是否合法：非空、带 sha256: 前缀且后接恰好 64 位小写十六进制字符。 */
    public static boolean isValid(String checksum) {
        if (checksum == null || !checksum.startsWith(PREFIX)) {
            return false;
        }
        String hex = checksum.substring(PREFIX.length());
        return hex.length() == 64 && LOWER_HEX_64.matcher(hex).matches();
    }
}
