package com.example.starter.observation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 请求指纹工具：以长度前缀编码拼接操作者、操作类型、规范化集合及所有影响结果的字段，
 * 不使用未转义分隔符，避免字段内容与前缀产生歧义；输出 SHA-256 十六进制摘要。
 */
final class Fingerprinting {

    private Fingerprinting() {
    }

    /**
     * 由若干净化后的字段构造指纹；null 字段以区别于任何非空编码的形式参与。
     */
    static String of(List<String> parts) {
        StringBuilder raw = new StringBuilder();
        for (String part : parts) {
            if (part == null) {
                raw.append("-1:");
            } else {
                raw.append(part.length()).append(':').append(part);
            }
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
