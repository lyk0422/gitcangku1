package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 测量记录。提交时按测量时刻匹配唯一有效证书，并固化未舍入计算值与合格判定。
 *
 * <p>原始提交 {@code version=1}、{@code revisionOf=null}、{@code rootId} 指向自身；
 * 复核驳回后的后继修订另起版本链，{@code version} 从 1 开始，{@code revisionOf} 指向前驱测量，
 * {@code rootId} 指向最初原始测量。同一测量至多存在一条后继修订（唯一约束）。
 *
 * @param id             测量记录 ID（自增）
 * @param measurementKey 业务测量键，全局唯一（幂等键）
 * @param instrumentId   仪器 ID
 * @param measuredAt     测量时刻（UTC）
 * @param rawReading     原始读数，最多 6 位小数
 * @param lowerLimit     合格下限（含端点）
 * @param upperLimit     合格上限（含端点）
 * @param submittedBy    提交人
 * @param certificateId  提交时匹配到的校准证书 ID；修订保留原证书与原始输入
 * @param computedValue  未舍入计算值 a×读数+b
 * @param passed         是否合格（基于未舍入值，含端点）
 * @param status         状态：PENDING 待放行 / RELEASED 已放行 / REJECTED 复核驳回
 * @param version        版本号：原始测量为 1，修订链自 1 重新编号
 * @param revisionOf     前驱测量 ID；null 表示原始提交
 * @param rootId         修订链根测量 ID；原始测量指向自身
 * @param note           测量说明或修订原因；null 表示无说明
 * @param createdAt      提交时间（UTC）
 */
public record Measurement(
        long id,
        String measurementKey,
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
        int version,
        Long revisionOf,
        Long rootId,
        String note,
        Instant createdAt) {

    /**
     * 原始提交构造：version=1、revisionOf=null、rootId 由持久层回填为自身、无说明。
     */
    public Measurement(long id, String measurementKey, String instrumentId, Instant measuredAt,
                       BigDecimal rawReading, BigDecimal lowerLimit, BigDecimal upperLimit,
                       String submittedBy, long certificateId, BigDecimal computedValue,
                       boolean passed, MeasurementStatus status, Instant createdAt) {
        this(id, measurementKey, instrumentId, measuredAt, rawReading, lowerLimit, upperLimit,
                submittedBy, certificateId, computedValue, passed, status, 1, null, null, null, createdAt);
    }

    /** 是否为修订记录（存在前驱测量）。 */
    public boolean isRevision() {
        return revisionOf != null;
    }

    /**
     * 显示值：未舍入计算值按 HALF_UP 保留 4 位小数；仅用于展示，不参与合格判定。
     */
    public BigDecimal displayValue() {
        return computedValue.setScale(4, java.math.RoundingMode.HALF_UP);
    }
}
