package com.example.starter.firmware.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 分片摘要规则（全系统唯一实现，登记与完整性判定共用）：
 * 摘要为 64 位小写十六进制 SHA-256；聚合摘要 = SHA-256(按分片序号升序拼接的各分片摘要字符串)。
 */
public final class DigestRules {

    private static final HexFormat HEX = HexFormat.of();

    private DigestRules() {
    }

    public static boolean isSha256Hex(String digest) {
        if (digest == null || digest.length() != 64) {
            return false;
        }
        for (int i = 0; i < digest.length(); i++) {
            char c = digest.charAt(i);
            boolean digit = c >= '0' && c <= '9';
            boolean lower = c >= 'a' && c <= 'f';
            if (!digit && !lower) {
                return false;
            }
        }
        return true;
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 按分片序号升序拼接摘要后取 SHA-256。入参必须已按序号升序排列。
     */
    public static String aggregateOfOrderedDigests(List<String> orderedDigests) {
        return sha256Hex(String.join("", orderedDigests));
    }
}
