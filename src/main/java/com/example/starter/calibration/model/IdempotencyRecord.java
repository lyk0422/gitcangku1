package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 幂等去重记录。写请求携带全局 requestId 时创建；占位行与业务在同一事务提交，
 * 业务失败整体回滚（失败不占键）；并发同键由主键串行化，冲突方重放首次完整响应。
 *
 * @param requestId      全局幂等请求 ID
 * @param actor          操作者（非空）
 * @param fingerprint    规范化参数指纹（JSON：操作、资源、全参数、期望版本）
 * @param responseStatus 首次响应 HTTP 状态；业务完成前为 null
 * @param responseBody   首次完整响应体（JSON）；业务完成前为 null
 * @param createdAt      记录创建时间（UTC）
 */
public record IdempotencyRecord(
        String requestId,
        String actor,
        String fingerprint,
        Integer responseStatus,
        String responseBody,
        Instant createdAt) {
}
