package com.example.starter.firmware.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 幂等指纹构造。指纹必须包含操作者、操作类型、规范化对象集合及所有影响结果的字段：
 * 字段按名字典序组织为 JSON，集合按字典序排序去重后整体序列化，
 * 不使用未转义分隔符拼接，杜绝元素内含分隔符造成的碰撞。最终外覆 SHA-256 十六进制摘要。
 */
@Component
public class Fingerprints {

    private final ObjectMapper objectMapper;

    public Fingerprints(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 开始演练指纹：操作者、操作类型、型号/版本字段与规范化设备集合。
     */
    public String startRehearsal(String operator, String model, String targetVersion,
                                 String rollbackVersion, List<String> normalizedDeviceIds) {
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("operator", operator);
        canonical.put("operation", "rehearsal.start");
        canonical.put("model", model);
        canonical.put("targetVersion", targetVersion);
        canonical.put("rollbackVersion", rollbackVersion);
        canonical.put("deviceIds", normalizedDeviceIds);
        return digest(canonical);
    }

    /**
     * 提交演练结果指纹：操作者、操作类型、演练ID与规范化（按设备ID排序）的每台结果集合。
     */
    public String submitRehearsal(String operator, long rehearsalId,
                                  List<Map<String, String>> normalizedResults) {
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("operator", operator);
        canonical.put("operation", "rehearsal.submit");
        canonical.put("rehearsalId", rehearsalId);
        canonical.put("results", normalizedResults);
        return digest(canonical);
    }

    /**
     * 通用字段指纹：操作者、操作类型与全部命名字段（按字典序）。
     */
    public String of(String operator, String operation, Map<String, ?> fields) {
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("operator", operator);
        canonical.put("operation", operation);
        canonical.putAll(fields);
        return digest(canonical);
    }

    private String digest(Map<String, ?> canonical) {
        try {
            String json = objectMapper.writeValueAsString(canonical);
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等指纹序列化失败", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
