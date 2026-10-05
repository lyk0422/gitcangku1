package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 差分记录响应。referenceValue/dutValue/diffValue 为冻结的未舍入精确值；
 * displayDiff 为 HALF_UP 保留 4 位的显示差值；usable 表示“当前可用”
 * （已放行且双方证书均未撤销）。
 *
 * @param id                     差分记录 ID
 * @param instrumentId           仪器 ID
 * @param referenceMeasurementId 参考测量记录 ID
 * @param referenceKey           参考测量键
 * @param dutMeasurementId       待测测量记录 ID
 * @param dutKey                 待测测量键
 * @param referenceCertificateId 参考测量冻结的证书 ID
 * @param dutCertificateId       待测测量冻结的证书 ID
 * @param referenceValue         参考校正值（未舍入，十进制字符串）
 * @param dutValue               待测校正值（未舍入，十进制字符串）
 * @param diffValue              差值=待测-参考（未舍入，十进制字符串）
 * @param displayDiff            显示差值，HALF_UP 4 位小数（十进制字符串）
 * @param lowerLimit             差值合格下限（含端点，十进制字符串）
 * @param upperLimit             差值合格上限（含端点，十进制字符串）
 * @param passed                 差值是否合格（基于未舍入值，含端点）
 * @param status                 状态：PENDING / RELEASED / CANCELLED
 * @param usable                 当前是否可用（已放行且双方证书未撤销）
 * @param version                乐观锁版本号
 * @param createdBy              创建人
 * @param createdAt              创建时间（UTC）
 * @param releasedBy             放行人，未放行时为 null
 * @param releasedAt             放行时间（UTC），未放行时为 null
 * @param cancelledBy            取消人，未取消时为 null
 * @param cancelledAt            取消时间（UTC），未取消时为 null
 */
public record DifferentialResponse(
        long id,
        String instrumentId,
        long referenceMeasurementId,
        String referenceKey,
        long dutMeasurementId,
        String dutKey,
        long referenceCertificateId,
        long dutCertificateId,
        String referenceValue,
        String dutValue,
        String diffValue,
        String displayDiff,
        String lowerLimit,
        String upperLimit,
        boolean passed,
        String status,
        boolean usable,
        int version,
        String createdBy,
        Instant createdAt,
        String releasedBy,
        Instant releasedAt,
        String cancelledBy,
        Instant cancelledAt) {
}
