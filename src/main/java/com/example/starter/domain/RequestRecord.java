package com.example.starter.domain;

/**
 * 请求去重记录：成功的写请求按 requestId 记录参数指纹与业务结果，
 * 与业务结果在同一事务提交。
 *
 * @param requestId   客户端请求 ID
 * @param operation   操作类型（REPLACE_DRAFT / PUBLISH / REVOKE_GRANT）
 * @param fingerprint 请求参数指纹，用于识别“同 requestId 不同参数”
 * @param resultJson  成功结果的 JSON 序列化，重试时原样返回
 */
public record RequestRecord(String requestId, String operation, String fingerprint,
                            String resultJson) {
}
