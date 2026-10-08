package com.example.starter.repo;

/**
 * 写操作幂等去重记录。
 *
 * @param requestId    写操作全局唯一请求标识
 * @param requestKind  请求类型（操作）
 * @param operator     首次成功请求的非空操作者标识
 * @param requestHash  规范化参数 SHA-256（覆盖资源、全参数与期望版本）
 * @param responseJson 首次成功响应 JSON
 * @param createdAt    首次成功时间，epoch 毫秒（UTC）
 */
public record DedupPo(String requestId, String requestKind, String operator,
                      String requestHash, String responseJson, long createdAt) {
}
