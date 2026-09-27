package com.example.starter.plan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * 写操作幂等指纹工具：对操作者、操作类型、规范化对象集合及全部影响结果的字段
 * 生成稳定的 SHA-256。
 *
 * <p>对象集合不使用未转义分隔符拼接：每个集合元素序列化为按键排序的独立 JSON 对象后，
 * 按其规范化 JSON 文本排序，再与其他字段一并序列化，保证字段书写顺序与集合元素提交顺序
 * 均不影响指纹，且字段内含分隔字符也不会造成碰撞。
 */
@Component
public class RequestFingerprint {

    private final ObjectMapper objectMapper;

    public RequestFingerprint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 构造按键排序的 Map，保证字段书写顺序不影响指纹。
     */
    public static Map<String, Object> orderedMap(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("keyValues 必须成对出现");
        }
        Map<String, Object> map = new TreeMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    /**
     * 对规范化根对象计算 SHA-256（64 位小写十六进制）。
     */
    public String sha256(Map<String, Object> canonicalRoot) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(
                    objectMapper.writeValueAsString(canonicalRoot).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException("幂等指纹序列化失败", e);
        }
    }
}
