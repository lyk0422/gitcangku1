package com.example.starter.incident;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 命令请求指纹工具：对操作者、操作类型与全部参数（集合经去重排序规范化）
 * 做 JSON 编码后取 SHA-256。集合以 JSON 数组编码，元素经 JSON 转义，
 * 不直接以未转义分隔符拼接，避免 ["A","B"] 与 ["A,B"] 碰撞。
 */
final class RequestFingerprints {

    private RequestFingerprints() {
    }

    /**
     * 计算规范化指纹。params 中的 List 必须在调用前去重排序（或内容本身不区分顺序）。
     */
    static String sha256(ObjectMapper objectMapper, String operation, String actor,
                         Map<String, Object> params) {
        String json = canonical(objectMapper, operation, actor, params);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 规范化 JSON 文本（操作类型、操作者、参数顺序固定）。 */
    static String canonical(ObjectMapper objectMapper, String operation, String actor,
                            Map<String, Object> params) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("operation", operation);
        root.put("actor", actor);
        root.put("params", params);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("请求指纹序列化失败", e);
        }
    }
}
