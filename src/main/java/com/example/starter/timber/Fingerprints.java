package com.example.starter.timber;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 幂等参数指纹：把操作与规范化后的参数串接后取SHA-256，同参重放判等、异参判409。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    public static String of(String operation, List<String> parts) {
        StringBuilder canonical = new StringBuilder(operation);
        for (String part : parts) {
            canonical.append('|').append(part == null ? "" : part);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}