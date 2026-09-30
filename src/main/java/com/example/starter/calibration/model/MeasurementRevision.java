package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 测量修订版本。每版封存一份目标值与允许偏差；放行后 {@code releasedAt} 非空，
 * 该修订成为不可变放行快照的一部分，后续测量修订（新版本号）不得改写它。
 *
 * @param id         修订记录 ID（自增）
 * @param batchId    所属批次 ID
 * @param revisionNo 版本号，批次内从 1 递增
 * @param targetValue 目标值；允许区间为 [targetValue-tolerance, targetValue+tolerance]
 * @param tolerance  允许偏差（非负）
 * @param reason     修订原因；首版为 null，后续修订非空
 * @param createdBy  修订创建人
 * @param createdAt  修订创建时间（UTC）
 * @param releasedAt 该修订放行时间（UTC）；未放行为 null
 */
public record MeasurementRevision(
        long id,
        long batchId,
        int revisionNo,
        BigDecimal targetValue,
        BigDecimal tolerance,
        String reason,
        String createdBy,
        Instant createdAt,
        Instant releasedAt) {

    /** 允许下限（含端点）= 目标值 - 偏差。 */
    public BigDecimal lowerLimit() {
        return targetValue.subtract(tolerance);
    }

    /** 允许上限（含端点）= 目标值 + 偏差。 */
    public BigDecimal upperLimit() {
        return targetValue.add(tolerance);
    }
}
