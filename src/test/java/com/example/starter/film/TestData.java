package com.example.starter.film;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 测试用合成摘要：由标签确定性生成符合 {@code sha256:<64位十六进制>} 格式的字符串。
 */
final class TestData {

    private TestData() {
    }

    static String digest(String tag) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(tag.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return "sha256:" + hex;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
