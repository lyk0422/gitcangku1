package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 幂等去重记录。新写请求按全局 requestId 去重，与业务写入同事务原子提交；
 * 仅成功响应被记录，业务失败随事务回滚不占键。仅保留同 JVM 内状态。
 *
 * @param id                记录 ID（自增）
 * @param requestId         全局幂等请求 ID
 * @param operation         操作类型
 * @param actor             操作者（非空）
 * @param resource          资源标识（如仪器 ID、测量键）
 * @param paramsFingerprint 规范化参数指纹（JSON：操作、资源、全参数、期望版本）
 * @param responseStatus    首次响应 HTTP 状态；业务完成前为 null
 * @param responseBody      首次完整响应体（JSON）；业务完成前为 null
 * @param createdAt         记录创建时间（UTC）
 */
public record IdempotencyRecord(
        long id,
        String requestId,
        String operation,
        String actor,
        String resource,
        String paramsFingerprint,
        Integer responseStatus,
        String responseBody,
        Instant createdAt) {
}
