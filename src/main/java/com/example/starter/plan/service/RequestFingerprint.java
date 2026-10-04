package com.example.starter.plan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 幂等请求指纹：将操作身份（操作类型、操作者、资源、全部参数与期望版本）按结构化
 * JSON 序列化后取 SHA-256，避免字符串拼接分隔符与参数内容碰撞。
 */
@Component
public class RequestFingerprint {

    private final ObjectMapper objectMapper;

    public RequestFingerprint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 计算身份指纹。identity 为有序键值结构，相同语义输入必得相同指纹。
     */
    public String of(Map<String, ?> identity) {
        try {
            return sha256(objectMapper.writeValueAsBytes(identity));
        } catch (Exception e) {
            throw new IllegalStateException("请求指纹序列化失败", e);
        }
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(data);
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