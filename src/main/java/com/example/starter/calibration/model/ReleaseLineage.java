package com.example.starter.calibration.model;

/**
 * 重新放行逐项血缘：新批次每个位置对应来源批次的哪个测量记录。
 *
 * @param id                   血缘记录 ID（自增）
 * @param batchId              重新放行生成的新批次 ID
 * @param measurementId        新批次中使用的测量记录 ID
 * @param sourceBatchId        来源批次 ID
 * @param sourceMeasurementId  来源批次中对应位置的测量记录 ID
 */
public record ReleaseLineage(
        long id,
        String batchId,
        long measurementId,
        String sourceBatchId,
        long sourceMeasurementId) {
}
