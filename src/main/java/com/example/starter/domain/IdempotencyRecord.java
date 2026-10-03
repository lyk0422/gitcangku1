package com.example.starter.domain;

/**
 * 幂等请求记录：同参重放返回原结果，异参返回 409。
 *
 * @param scope 幂等作用域，形如 segment:<井号>、sample:<井号>、void:<井号>、switch:<井号>、decision:<申请id>
 * @param requestId 客户端请求编号
 * @param payloadHash 请求参数规范化后的 SHA-256 摘要（十六进制）
 * @param entityId 该请求产生或决定的实体主键
 * @param httpStatus 首次处理返回的 HTTP 状态码，重放时保持一致
 * @param errorMessage 首次处理返回的错误消息；NULL 表示成功
 */
public record IdempotencyRecord(
        String scope,
        String requestId,
        String payloadHash,
        long entityId,
        int httpStatus,
        String errorMessage) {
}