package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 测量记录（一个修订版本）。提交/修订时按测量时刻匹配唯一有效证书，并固化未舍入计算值与合格判定。
 * 仪器与测量 UTC 时刻在同一 measurementKey 的所有版本间保持不变。
 *
 * @param id             测量版本记录 ID（自增）
 * @param measurementKey 业务测量键
 * @param revision       修订版本号，从 1 开始；同一键内唯一递增
 * @param instrumentId   仪器 ID
 * @param measuredAt     测量时刻（UTC）
 * @param rawReading     原始读数，最多 6 位小数
 * @param lowerLimit     合格下限（含端点）
 * @param upperLimit     合格上限（含端点）
 * @param submittedBy    原提交人
 * @param certificateId  提交/修订时匹配到的校准证书 ID
 * @param computedValue  未舍入计算值 a×读数+b
 * @param passed         是否合格（基于未舍入值，含端点）
 * @param status         状态：PENDING 待放行 / RELEASED 已放行
 * @param revisionReason 修订原因；第 1 版为 null，修订版非空
 * @param revisedBy      修订操作人（X-Actor-Id，即原提交人）；第 1 版为 null
 * @param revisedAt      修订时间（UTC）；第 1 版为 null
 * @param createdAt      该版本创建时间（UTC）
 */
public record Measurement(
        long id,
        String measurementKey,
        int revision,
        String instrumentId,
        Instant measuredAt,
        BigDecimal rawReading,
        BigDecimal lowerLimit,
        BigDecimal upperLimit,
        String submittedBy,
        long certificateId,
        BigDecimal computedValue,
        boolean passed,
        MeasurementStatus status,
        String revisionReason,
        String revisedBy,
        Instant revisedAt,
        Instant createdAt) {

    /**
     * 显示值：未舍入计算值按 HALF_UP 保留 4 位小数；仅用于展示，不参与合格判定。
     */
    public BigDecimal displayValue() {
        return computedValue.setScale(4, java.math.RoundingMode.HALF_UP);
    }
}
