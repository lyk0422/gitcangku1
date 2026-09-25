package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 处置风险记录响应：已放行批次新增未裁决 MAJOR 偏差、或批次被 MAJOR 偏差处置时写入，
 * 只增不改，历史放行记录不删除。
 */
public record DispositionRiskResponse(
        String batchKey,
        String excursionKey,
        String riskType,
        String detail,
        Instant createdAt
) {
}
