package com.example.starter.plan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/**
 * 写操作幂等身份：将操作、操作者、资源与全部参数组装为结构化 JSON 后取 SHA-256，
 * 避免分隔符拼接造成的身份碰撞。
 */
final class RequestIdentities {

    private RequestIdentities() {
    }

    /**
     * 以规范化 JSON（LinkedHashMap 保持字段顺序）序列化身份并取 SHA-256。
     */
    static String hash(ObjectMapper objectMapper, Map<String, Object> identity) {
        try {
            return sha256(objectMapper.writeValueAsString(identity));
        } catch (Exception e) {
            throw new IllegalStateException("幂等身份序列化失败", e);
        }
    }

    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
