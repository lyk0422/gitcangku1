package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 放行诊断响应：批次头与逐条测量的标准器、补偿系数、不确定度版本追溯。
 *
 * @param batchId    放行批次 ID
 * @param releasedBy 放行人
 * @param releasedAt 放行时间（UTC）
 * @param items      逐条追溯项
 */
public record ReleaseDiagnosticsResponse(
        String batchId,
        String releasedBy,
        Instant releasedAt,
        List<ReleaseDiagnosticsItem> items) {
}
