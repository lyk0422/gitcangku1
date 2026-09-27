package com.example.starter.calibration.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * 请求指纹工具。以规范化（键名字典序、集合显式排序、数值归一）的 JSON 作为摘要输入，
 * 再做 SHA-256；不使用任何未转义分隔符手工拼接，避免注入与歧义。
 *
 * <p>指纹要素：操作者、操作类型、规范化集合以及所有影响结果的字段。
 */
public final class Fingerprints {

    /** 规范化 JSON 输出器：Map 按键名字典序，不输出空白差异，BigDecimal 以数值归一输出。 */
    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(SerializationFeature.INDENT_OUTPUT, false);

    private Fingerprints() {
    }

    /**
     * 构造请求指纹。
     *
     * @param actor         操作者（X-Actor-Id）
     * @param operationType 操作类型
     * @param parameters    所有影响结果的字段；调用方须保证集合类字段已规范化排序
     * @return SHA-256 十六进制摘要（64 字符）
     */
    public static String of(String actor, String operationType, Map<String, ?> parameters) {
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("actor", actor);
        canonical.put("operationType", operationType);
        canonical.put("parameters", new TreeMap<>(parameters));
        try {
            byte[] json = CANONICAL.writeValueAsBytes(canonical);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalArgumentException("请求参数无法规范化为指纹", ex);
        }
    }
}
