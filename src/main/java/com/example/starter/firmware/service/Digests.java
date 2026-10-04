package com.example.starter.firmware.service;

import com.example.starter.firmware.error.ApiException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 镜像摘要工具：64 位十六进制 SHA-256，统一小写落库与比较。
 */
public final class Digests {

    private static final Pattern HEX_64 = Pattern.compile("[0-9a-f]{64}");

    private Digests() {
    }

    /**
     * 校验并统一小写；非法格式抛出 400。
     */
    public static String normalize(String digest, String field) {
        if (digest == null) {
            throw ApiException.badRequest("MISSING_DIGEST", field + " 不能为空");
        }
        String lowered = digest.toLowerCase(Locale.ROOT);
        if (!HEX_64.matcher(lowered).matches()) {
            throw ApiException.badRequest("INVALID_DIGEST", field + " 必须为64位十六进制: " + digest);
        }
        return lowered;
    }

    /**
     * 登记时未显式声明摘要，按设备与版本确定性合成当前镜像 SHA-256 摘要。
     */
    public static String synthesize(String deviceId, String currentVersion) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((deviceId + ":" + currentVersion).getBytes(StandardCharsets.UTF_8));
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
}