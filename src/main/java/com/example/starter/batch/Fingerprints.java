package com.example.starter.batch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 命令指纹：将操作者、操作类型、规范化集合及全部影响结果的字段组成的对象序列化为
 * 规范化 JSON（Map 键按字典序输出；集合由调用方排序后以数组承载，JSON 转义由 Jackson 完成），
 * 再取 SHA-256。不使用未转义分隔符手工拼接，避免分隔符碰撞。
 */
@Component
public class Fingerprints {

    private final ObjectMapper canonicalMapper;

    public Fingerprints(ObjectMapper objectMapper) {
        this.canonicalMapper = objectMapper.copy()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    /**
     * 计算规范化 JSON 的 SHA-256 十六进制摘要。
     */
    public String sha256(Object canonicalPayload) {
        String canonical;
        try {
            canonical = canonicalMapper.writeValueAsString(canonicalPayload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("命令指纹序列化失败", e);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
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
