package com.example.starter.firmware.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 设备模块清单摘要：按模块名升序拼接 "module=version" 行后取 SHA-256 十六进制。
 * 同一清单内容摘要稳定，与上报顺序无关。
 */
public final class ModuleDigest {

    private ModuleDigest() {
    }

    public static String of(Map<String, String> modules) {
        StringBuilder content = new StringBuilder();
        for (Map.Entry<String, String> entry : new TreeMap<>(modules).entrySet()) {
            content.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        return sha256Hex(content.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static String of(List<DeviceModuleEntry> modules) {
        Map<String, String> map = new TreeMap<>();
        for (DeviceModuleEntry entry : modules) {
            map.put(entry.module(), entry.version());
        }
        return of(map);
    }

    private static String sha256Hex(byte[] data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
