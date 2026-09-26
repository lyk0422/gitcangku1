package com.example.starter.firmware.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 分片摘要工具。摘要格式固定为小写十六进制 SHA-256（64字符）；
 * 聚合摘要为按分片序号升序拼接各分片摘要字符串后再次求 SHA-256。
 */
public final class Digests {

    public static final String DIGEST_PATTERN = "[0-9a-f]{64}";

    private Digests() {
    }

    public static boolean isValidDigest(String digest) {
        return digest != null && digest.matches(DIGEST_PATTERN);
    }

    /**
     * 按规范顺序拼接分片摘要后计算聚合摘要。
     */
    public static String aggregateHex(Iterable<String> orderedShardDigests) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
        for (String shardDigest : orderedShardDigests) {
            digest.update(shardDigest.getBytes(StandardCharsets.UTF_8));
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest.digest()) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
