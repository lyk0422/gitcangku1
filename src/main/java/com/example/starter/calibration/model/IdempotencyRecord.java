package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 幂等请求记录。requestId 全局唯一；身份指纹为操作、操作者、资源及全部参数（含期望版本）
 * 的规范化 JSON，结构化比较避免分隔符碰撞；首次成功响应随业务同一事务提交。
 *
 * @param requestId    幂等请求键（X-Request-Id）
 * @param fingerprint  请求身份指纹（规范化 JSON）
 * @param responseBody 首次成功响应的 JSON 报文；处理中或未成功时为 null
 * @param createdAt    记录创建时间（UTC）
 */
public record IdempotencyRecord(
        String requestId,
        String fingerprint,
        String responseBody,
        Instant createdAt) {
}
