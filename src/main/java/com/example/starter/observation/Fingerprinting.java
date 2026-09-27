package com.example.starter.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * 请求幂等指纹：将操作类型、操作者及所有影响结果的字段组装为列表后整体 JSON 序列化，
 * 再取 SHA-256。集合与标量都经 JSON 转义，不通过未转义分隔符拼接，
 * 因此字段值中出现任何字符都不会改变字段边界。
 *
 * <p>BigDecimal 先规范化为去尾零的纯数字字符串（12.340 与 12.34 指纹一致），
 * Instant 统一为 ISO-8601 UTC 原文。
 */
@Component
public class Fingerprinting {

    private final ObjectMapper objectMapper;

    public Fingerprinting(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 计算指纹：parts 按固定顺序给出，通常以操作类型开头，随后是操作者与规范化对象集合。
     */
    public String of(Object... parts) {
        List<Object> normalized = Arrays.stream(parts).map(Fingerprinting::normalizePart).toList();
        String json;
        try {
            json = objectMapper.writeValueAsString(normalized);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize fingerprint parts", e);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * 规范化单个指纹因子：BigDecimal 去尾零、Instant 转 ISO 原文，集合逐项递归规范化，
     * 其余类型原样交给 JSON 序列化。
     */
    private static Object normalizePart(Object part) {
        if (part instanceof BigDecimal decimal) {
            return decimal.signum() == 0 ? "0" : decimal.stripTrailingZeros().toPlainString();
        }
        if (part instanceof Instant instant) {
            return instant.toString();
        }
        if (part instanceof List<?> list) {
            return list.stream().map(Fingerprinting::normalizePart).toList();
        }
        return part;
    }
}
