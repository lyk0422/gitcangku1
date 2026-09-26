package com.example.starter.firmware.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 分片摘要工具：摘要固定为 64 位小写十六进制 SHA-256；
 * 完整包聚合摘要定义为按分片序号升序拼接全部分片摘要（十六进制字符串）后的 SHA-256。
 */
public final class Digests {

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    private Digests() {
    }

    public static boolean isSha256Hex(String value) {
        return value != null && SHA256_HEX.matcher(value).matches();
    }

    /**
     * 按序号升序拼接分片摘要后计算 SHA-256，返回 64 位小写十六进制。
     */
    public static String aggregate(List<String> chunkDigestsInOrder) {
        return sha256Hex(String.join("", chunkDigestsInOrder));
    }

    public static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
