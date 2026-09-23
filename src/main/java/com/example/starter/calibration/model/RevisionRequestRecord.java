package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 修订请求幂等记录。仅记录成功的修订请求；同 (measurementKey, requestId) 重放时用于比对参数。
 *
 * @param id               幂等记录 ID（自增）
 * @param measurementKey   业务测量键
 * @param requestId        客户端修订请求 ID
 * @param expectedRevision 请求携带的期望基准版本号
 * @param rawReading       首次请求的原始读数快照
 * @param lowerLimit       首次请求的合格下限快照
 * @param upperLimit       首次请求的合格上限快照
 * @param reason           首次请求的修订原因快照（非空）
 * @param actor            首次请求的原提交人（X-Actor-Id）
 * @param resultingRevision 首次成功产生的新版本号
 * @param createdAt        首次成功时间（UTC）
 */
public record RevisionRequestRecord(
        long id,
        String measurementKey,
        String requestId,
        int expectedRevision,
        BigDecimal rawReading,
        BigDecimal lowerLimit,
        BigDecimal upperLimit,
        String reason,
        String actor,
        int resultingRevision,
        Instant createdAt) {
}
