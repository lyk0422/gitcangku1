package com.example.starter.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 幂等指纹：对操作类型与规范化参数做 SHA-256，用于同键异参检测。
 */
@Component
public class FingerprintHasher {

    private final ObjectMapper objectMapper;

    public FingerprintHasher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 计算 operation 与参数对象的指纹。调用方需保证集合类参数顺序已规范化，
     * 同一语义请求产生相同指纹。
     */
    public String hash(String operation, Object params) {
        try {
            String canonical = objectMapper.writeValueAsString(new Object[]{operation, params});
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("幂等指纹计算失败", e);
        }
    }
}
