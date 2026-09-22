package com.example.starter.repo;

/**
 * 幂等去重记录。
 *
 * @param requestId    请求唯一标识
 * @param requestKind  请求类型（ZONE_CREATE 等）
 * @param requestHash  规范化参数哈希（SHA-256）
 * @param responseJson 首次成功响应 JSON
 * @param createdAt    创建时间（epoch 毫秒）
 */
public record DedupPo(String requestId, String requestKind, String requestHash,
                      String responseJson, long createdAt) {
}
