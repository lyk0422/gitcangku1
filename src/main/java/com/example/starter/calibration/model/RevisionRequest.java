package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 成功落库的测量修订幂等请求。同 measurement_key 下 requestId 唯一；
 * 同 requestId 重放时必须与首次参数（读数、上下限、原因）完全一致。
 *
 * @param id            幂等记录 ID（自增）
 * @param measurementKey 业务测量键
 * @param requestId     修订请求幂等 ID；同键内唯一
 * @param expectedRevision 请求携带的期望最新版本号
 * @param rawReading    请求的修订后原始读数
 * @param lowerLimit    请求的修订后合格下限
 * @param upperLimit    请求的修订后合格上限
 * @param reason        修订原因（非空）
 * @param measurementId 首次成功请求所创建版本的测量记录 ID
 * @param createdAt     首次成功提交时间（UTC）
 */
public record RevisionRequest(
        long id,
        String measurementKey,
        String requestId,
        int expectedRevision,
        BigDecimal rawReading,
        BigDecimal lowerLimit,
        BigDecimal upperLimit,
        String reason,
        long measurementId,
        Instant createdAt) {
}
