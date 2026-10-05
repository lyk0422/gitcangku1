package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 幂等去重记录。与业务写入同事务原子提交；仅成功请求占键，失败回滚后键可复用。
 *
 * @param requestId    幂等请求键（请求体 requestId）
 * @param operation    操作类型：CREATE_CERTIFICATE/REVOKE_CERTIFICATE/SUBMIT_MEASUREMENT/RELEASE
 * @param fingerprint  请求身份指纹：操作、操作者、资源、全部参数与期望版本的结构化 JSON
 * @param responseBody 首次成功的完整响应体（JSON）；处理中为 null
 * @param createdAt    记录创建时间（UTC）
 * @param completedAt  首次成功响应写入时间（UTC）；未完成为 null
 */
public record IdempotencyRecord(
        String requestId,
        String operation,
        String fingerprint,
        String responseBody,
        Instant createdAt,
        Instant completedAt) {
}
