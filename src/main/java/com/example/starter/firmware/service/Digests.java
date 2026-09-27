package com.example.starter.firmware.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 摘要工具：SHA-256 小写十六进制。聚合摘要定义为按分片序号升序拼接各分片摘要（64位小写十六进制字符串）
 * 后再计算 SHA-256。
 */
public final class Digests {

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    private Digests() {
    }

    /**
     * 是否为固定格式的摘要：64位小写十六进制。
     */
    public static boolean isSha256Hex(String value) {
        return value != null && SHA256_HEX.matcher(value).matches();
    }

    /**
     * 对输入字符串计算 SHA-256，返回64位小写十六进制。
     */
    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 聚合摘要：按序号升序拼接各分片摘要后计算 SHA-256。
     */
    public static String aggregateOf(List<String> digestsInIndexOrder) {
        return sha256Hex(String.join("", digestsInIndexOrder));
    }
}
