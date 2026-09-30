package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 放行幂等快照。仅在整批放行成功提交时随业务数据同一事务写入；
 * 重放同一 requestId 时原样返回，不重新计算。
 *
 * @param requestId             放行请求 ID（幂等键）
 * @param batchId               首次放行批次 ID
 * @param releasedBy            首次放行人
 * @param temperature           首次放行环境温度（℃）
 * @param humidity              首次放行环境湿度（%RH）
 * @param coefficientVersionTag 首次放行命中的系数版本标签
 * @param releasedAt            首次放行时间（UTC）
 * @param keys                  首次放行请求的测量键列表（保序）
 * @param createdAt             快照创建时间（UTC）
 */
public record ReleaseSnapshot(
        String requestId,
        String batchId,
        String releasedBy,
        BigDecimal temperature,
        BigDecimal humidity,
        String coefficientVersionTag,
        Instant releasedAt,
        List<String> keys,
        Instant createdAt) {
}
