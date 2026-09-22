package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 测量记录的一个不可变版本。提交时按测量时刻匹配唯一有效证书，并固化未舍入计算值与合格判定。
 * 同一 measurement_key 可有多个版本：revision 从 1 起递增；旧版本原样保留。
 *
 * @param id             测量记录 ID（自增）
 * @param measurementKey 业务测量键，同键多版本共享
 * @param revision       修订版本号；首次提交为 1
 * @param instrumentId   仪器 ID；同键各版本保持不变
 * @param measuredAt     测量时刻（UTC）；同键各版本保持不变
 * @param rawReading     原始读数，最多 6 位小数
 * @param lowerLimit     合格下限（含端点）
 * @param upperLimit     合格上限（含端点）
 * @param submittedBy    原提交人；同键各版本保持不变，仅其本人可修订
 * @param certificateId  本版本匹配到的校准证书 ID
 * @param computedValue  未舍入计算值 a×读数+b
 * @param passed         是否合格（基于未舍入值，含端点）
 * @param status         状态：PENDING 待放行 / RELEASED 已放行
 * @param revisionReason 修订原因；第 1 版为 null，修订版本非空
 * @param requestId      修订幂等请求 ID；第 1 版为 null
 * @param revisedBy      修订提交人（即原提交人）；第 1 版为 null
 * @param revisedAt      修订提交时间（UTC）；第 1 版为 null
 * @param createdAt      本版本创建时间（UTC）
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
        String requestId,
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
