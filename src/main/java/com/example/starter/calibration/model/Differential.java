package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * 差分记录。创建时绑定同一仪器的参考/待测两条测量，并冻结双方证书与未舍入精确值；
 * 差值 = 待测校正值 - 参考校正值，合格判定使用未舍入差值且包含端点。
 *
 * @param id                     差分记录 ID（自增）
 * @param instrumentId           仪器 ID（参考与待测同仪器）
 * @param referenceMeasurementId 参考测量记录 ID
 * @param dutMeasurementId       待测测量记录 ID
 * @param referenceCertificateId 参考测量冻结的证书 ID
 * @param dutCertificateId       待测测量冻结的证书 ID
 * @param referenceValue         参考测量冻结的未舍入校正值
 * @param dutValue               待测测量冻结的未舍入校正值
 * @param diffValue              未舍入差值（待测 - 参考）
 * @param lowerLimit             差值合格下限（含端点）
 * @param upperLimit             差值合格上限（含端点）
 * @param passed                 差值是否合格（基于未舍入值，含端点）
 * @param status                 状态：PENDING 待审 / RELEASED 已放行 / CANCELLED 已取消
 * @param version                乐观锁版本号，放行/取消时递增
 * @param createdBy              创建人
 * @param createdAt              创建时间（UTC）
 * @param releasedBy             放行人，未放行时为 null
 * @param releasedAt             放行时间（UTC），未放行时为 null
 * @param cancelledBy            取消人，未取消时为 null
 * @param cancelledAt            取消时间（UTC），未取消时为 null
 */
public record Differential(
        long id,
        String instrumentId,
        long referenceMeasurementId,
        long dutMeasurementId,
        long referenceCertificateId,
        long dutCertificateId,
        BigDecimal referenceValue,
        BigDecimal dutValue,
        BigDecimal diffValue,
        BigDecimal lowerLimit,
        BigDecimal upperLimit,
        boolean passed,
        DifferentialStatus status,
        int version,
        String createdBy,
        Instant createdAt,
        String releasedBy,
        Instant releasedAt,
        String cancelledBy,
        Instant cancelledAt) {

    /**
     * 显示差值：未舍入差值按 HALF_UP 保留 4 位小数；仅用于展示，不参与合格判定。
     */
    public BigDecimal displayDiff() {
        return diffValue.setScale(4, RoundingMode.HALF_UP);
    }
}
