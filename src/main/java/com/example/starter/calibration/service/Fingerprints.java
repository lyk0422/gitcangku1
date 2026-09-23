package com.example.starter.calibration.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * 幂等指纹工具：将请求参数规范化为与顺序无关的 SHA-256 指纹，用于同键异参检测。
 */
final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * 对 scope 与一组已规范化的项（每项一个字符串）计算顺序无关的 SHA-256 指纹（十六进制）。
     */
    static String of(String scope, List<String> items) {
        String canonical = scope + "\u0001" + items.stream().sorted()
                .reduce((a, b) -> a + "\u0002" + b).orElse("");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
