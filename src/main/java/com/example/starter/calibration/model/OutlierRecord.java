package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 离群记录。检测时封存目标值、允许偏差、读数与实际偏差，证据字段不可改写；
 * 仅裁决状态随复核推进（PENDING → ACCEPTED / REJECTED）。
 *
 * @param id               离群记录 ID（自增）
 * @param batchId          所属批次 ID
 * @param batchRevision    检测时的批次修订版本号
 * @param readingId        离群读数 ID
 * @param targetValue      封存目标值（检测时批次配置）
 * @param allowedDeviation 封存允许偏差（检测时批次配置）
 * @param readingValue     封存读数（校准计算值）
 * @param actualDeviation  实际偏差 |读数-目标值|
 * @param status           裁决状态：PENDING 待裁决 / ACCEPTED 保留 / REJECTED 排除
 * @param detectedAt       离群检测时间（UTC）
 * @param verdictAt        裁决形成时间（UTC）；未裁决时为 null
 */
public record OutlierRecord(
        long id,
        long batchId,
        int batchRevision,
        long readingId,
        BigDecimal targetValue,
        BigDecimal allowedDeviation,
        BigDecimal readingValue,
        BigDecimal actualDeviation,
        OutlierStatus status,
        Instant detectedAt,
        Instant verdictAt) {

    /**
     * 差额：实际偏差超出允许偏差的部分（actualDeviation - allowedDeviation）。
     */
    public BigDecimal excessDeviation() {
        return actualDeviation.subtract(allowedDeviation);
    }
}
