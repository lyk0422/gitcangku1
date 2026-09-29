package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 写接口幂等记录。仅成功提交会落库（失败随事务回滚，不占键）。
 *
 * @param requestId        客户端请求幂等键（X-Request-Id）
 * @param operator         操作者（X-Actor-Id），参与指纹
 * @param operationType    操作类型：THRESHOLD_PUBLISH / BUDGET_REVISE
 * @param fingerprint      请求指纹：规范化 JSON 的 SHA-256 十六进制
 * @param httpStatus       首次成功响应的 HTTP 状态码
 * @param responseSnapshot 首次成功完整响应体 JSON 快照
 * @param createdAt        首次成功提交时间（UTC）
 */
public record IdempotentRequest(
        String requestId,
        String operator,
        String operationType,
        String fingerprint,
        int httpStatus,
        String responseSnapshot,
        Instant createdAt) {
}
