package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 幂等请求记录。仅成功结论占键；指纹由操作者、操作类型、规范化参数集合及所有影响结果字段构造。
 *
 * @param requestId   客户端幂等键（X-Request-Id）
 * @param operation   操作类型：UPDATE_BUDGET/REVISE_MEASUREMENT/RELEASE_BUDGET/REVOKE_CERTIFICATE
 * @param fingerprint 规范化请求指纹（SHA-256 十六进制）
 * @param responseJson 首次成功的完整响应 JSON 快照
 * @param createdAt   首次成功时间（UTC）
 */
public record IdempotentRequest(
        String requestId,
        String operation,
        String fingerprint,
        String responseJson,
        Instant createdAt) {
}
