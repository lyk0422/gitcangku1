package com.example.starter.plan.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * 写操作幂等指纹：以规范化 JSON（字段名字典序、Map 键字典序、集合按给定顺序）作为载荷，
 * 覆盖操作者、操作类型与全部结果相关字段；不使用任何未转义分隔符手工拼接，
 * 字段值中的换行、引号等由 JSON 编码负责转义，杜绝键值越界碰撞。
 */
@Component
public class RequestFingerprint {

    private final ObjectMapper canonicalMapper;

    public RequestFingerprint(ObjectMapper objectMapper) {
        this.canonicalMapper = objectMapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    /**
     * 计算 (操作类型, 操作者, 规范化载荷) 的 SHA-256 十六进制指纹。
     *
     * @param opType   操作类型
     * @param operator 操作者标识，null 按空串归一
     * @param payload  全部结果相关字段组成的对象（record/Map/集合均可）
     */
    public String sha256(String opType, String operator, Object payload) {
        Map<String, Object> root = new TreeMap<>();
        root.put("op", opType);
        root.put("operator", operator == null ? "" : operator);
        root.put("payload", payload);
        byte[] json;
        try {
            json = canonicalMapper.writeValueAsBytes(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等指纹规范化失败", e);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(json);
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

    /**
     * 规范化载荷的 UTF-8 JSON 字节，主要用于测试断言指纹稳定性，不参与持久化。
     */
    public byte[] canonicalBytes(Object payload) {
        try {
            return canonicalMapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("规范化序列化失败", e);
        }
    }

    /**
     * 以 UTF-8 解码规范化 JSON，便于调试与测试比对。
     */
    public String canonicalJson(String opType, String operator, Object payload) {
        Map<String, Object> root = new TreeMap<>();
        root.put("op", opType);
        root.put("operator", operator == null ? "" : operator);
        root.put("payload", payload);
        try {
            return new String(canonicalMapper.writeValueAsBytes(root), StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("规范化序列化失败", e);
        }
    }
}
