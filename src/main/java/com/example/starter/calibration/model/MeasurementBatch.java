package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 测量批次。目标值与允许偏差存放在各 {@link MeasurementRevision} 中；
 * 批次行只持有当前修订号与放行状态。
 *
 * @param id                批次 ID（自增）
 * @param batchKey          业务批次键，全局唯一
 * @param instrumentId      仪器 ID
 * @param currentRevisionNo 当前测量修订版本号，从 1 开始
 * @param status            批次状态：OPEN / RELEASED
 * @param createdBy         批次创建人
 * @param createdAt         创建时间（UTC）
 */
public record MeasurementBatch(
        long id,
        String batchKey,
        String instrumentId,
        int currentRevisionNo,
        BatchStatus status,
        String createdBy,
        Instant createdAt) {
}
