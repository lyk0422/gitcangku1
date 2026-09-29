package com.example.starter.calibration.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * 请求指纹：以规范化 JSON（字段名按字典序、集合先排序、数值统一 toPlainString）
 * 做 SHA-256，避免未转义分隔符拼接造成的二义性。
 * 操作者、操作类型、规范化集合及全部影响结果字段都须纳入指纹。
 */
public final class Fingerprints {

    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Fingerprints() {
    }

    /**
     * 计算规范化 JSON 的 SHA-256 十六进制指纹。入参用有序 Map/List 表达规范化结构。
     */
    public static String sha256(Object canonicalStructure) {
        try {
            byte[] json = CANONICAL.writeValueAsBytes(canonicalStructure);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json);
            char[] out = new char[hash.length * 2];
            for (int i = 0; i < hash.length; i++) {
                out[i * 2] = HEX[(hash[i] >> 4) & 0x0F];
                out[i * 2 + 1] = HEX[hash[i] & 0x0F];
            }
            return new String(out);
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("构造请求指纹失败", ex);
        }
    }

    /**
     * 创建按字段名字典序排列的规范化容器。
     */
    public static Map<String, Object> ordered() {
        return new TreeMap<>();
    }

    static String canonicalJson(Object canonicalStructure) {
        try {
            return CANONICAL.writeValueAsString(canonicalStructure);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("序列化规范化 JSON 失败", ex);
        }
    }
}
