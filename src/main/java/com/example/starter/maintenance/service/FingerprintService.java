package com.example.starter.maintenance.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;

import com.example.starter.maintenance.api.dto.LockPointSpec;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 请求指纹：以操作类型、操作者、规范化集合及全部影响结果字段构造结构化 JSON，
 * 再做 SHA-256 摘要；不使用未转义分隔符拼接字符串，避免字段内容注入导致碰撞。
 * 集合一律按稳定键排序后入指纹（规范化集合），与响应中保留的提交顺序互不影响。
 */
@Service
public class FingerprintService {

    private final ObjectMapper canonicalMapper;

    public FingerprintService(ObjectMapper objectMapper) {
        // 复用容器 ObjectMapper（已注册 JSR-310 模块，Instant 按 UTC ISO-8601 序列化）
        this.canonicalMapper = objectMapper;
    }

    /** 将字段按 key 字典序排列（TreeMap）后序列化为规范化 JSON，并计算 SHA-256（十六进制）。 */
    public String sha256(Map<String, Object> fields) {
        try {
            String json = canonicalMapper.writeValueAsString(new TreeMap<>(fields));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 摘要算法不可用", e);
        } catch (Exception e) {
            throw new IllegalStateException("请求指纹序列化失败", e);
        }
    }

    /** 字符串集合的规范化 JSON 数组（调用方负责先排序）；用于证据快照，避免分隔符歧义。 */
    public String toJsonArray(List<String> values) {
        try {
            return canonicalMapper.writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalStateException("集合序列化失败", e);
        }
    }

    /** 锁定点规范化集合：按锁定点标识字典序排列，描述作为同标识点的载荷字段一并入指纹。 */
    public List<Map<String, Object>> normalizeLockPoints(List<LockPointSpec> lockPoints) {
        return lockPoints.stream()
                .sorted(java.util.Comparator.comparing(LockPointSpec::lockPointId))
                .map(point -> {
                    Map<String, Object> entry = new TreeMap<>();
                    entry.put("description", point.description());
                    entry.put("lockPointId", point.lockPointId());
                    return entry;
                })
                .toList();
    }
}
