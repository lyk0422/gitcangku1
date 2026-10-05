package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * 差分结果。绑定同仪器、UTC 采样时刻相差不超过 10 秒的参考/待测两条测量；
 * 差值=待测校正值-参考校正值，合格判定基于未舍入差值与闭区间限值。
 *
 * @param id                     差分记录 ID（自增）
 * @param differentialKey        业务差分键，全局唯一
 * @param referenceMeasurementId 参考件测量记录 ID
 * @param dutMeasurementId       待测件测量记录 ID
 * @param instrumentId           仪器 ID（两条测量同仪器）
 * @param referenceCertificateId 参考测量固化的证书 ID
 * @param dutCertificateId       待测测量固化的证书 ID
 * @param difference             未舍入差值=待测校正值-参考校正值
 * @param lowerLimit             差分合格下限（闭区间，含端点）
 * @param upperLimit             差分合格上限（闭区间，含端点）
 * @param passed                 差分是否合格（基于未舍入差值）
 * @param status                 状态：PENDING 待审 / RELEASED 已放行 / CANCELLED 已取消
 * @param createdBy              创建人
 * @param createdAt              创建时间（UTC）
 * @param releasedBy             放行人，未放行为 null
 * @param releasedAt             放行时间（UTC），未放行为 null
 * @param frozenReferenceValue   放行时冻结的参考校正值（未舍入），未放行为 null
 * @param frozenDutValue         放行时冻结的待测校正值（未舍入），未放行为 null
 * @param cancelledBy            取消人，未取消为 null
 * @param cancelledAt            取消时间（UTC），未取消为 null
 * @param version                乐观锁版本，放行/取消时递增
 */
public record Differential(
        long id,
        String differentialKey,
        long referenceMeasurementId,
        long dutMeasurementId,
        String instrumentId,
        long referenceCertificateId,
        long dutCertificateId,
        BigDecimal difference,
        BigDecimal lowerLimit,
        BigDecimal upperLimit,
        boolean passed,
        DifferentialStatus status,
        String createdBy,
        Instant createdAt,
        String releasedBy,
        Instant releasedAt,
        BigDecimal frozenReferenceValue,
        BigDecimal frozenDutValue,
        String cancelledBy,
        Instant cancelledAt,
        long version) {

    /**
     * 显示差值：未舍入差值按 HALF_UP 保留 4 位小数；仅用于展示，不参与合格判定。
     */
    public BigDecimal displayDifference() {
        return difference.setScale(4, RoundingMode.HALF_UP);
    }
}
