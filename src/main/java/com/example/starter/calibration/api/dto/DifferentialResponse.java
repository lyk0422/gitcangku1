package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 差分明细响应。difference 为未舍入精确差值；displayDifference 为 HALF_UP 4 位显示值；
 * usable 表示“当前可用”（已放行且双方证书均未撤销）。
 *
 * @param id                     差分记录 ID
 * @param differentialKey        业务差分键
 * @param instrumentId           仪器 ID
 * @param referenceMeasurementKey 参考件测量键
 * @param dutMeasurementKey      待测件测量键
 * @param referenceCertificateId 参考测量固化的证书 ID
 * @param dutCertificateId       待测测量固化的证书 ID
 * @param difference             未舍入差值=待测校正值-参考校正值（十进制字符串）
 * @param displayDifference      显示差值，HALF_UP 4 位小数（十进制字符串）
 * @param lowerLimit             差分合格下限（十进制字符串）
 * @param upperLimit             差分合格上限（十进制字符串）
 * @param passed                 差分是否合格（基于未舍入差值，闭区间含端点）
 * @param status                 状态：PENDING / RELEASED / CANCELLED
 * @param usable                 当前是否可用（已放行且双方证书未撤销）
 * @param version                乐观锁版本
 * @param createdBy              创建人
 * @param createdAt              创建时间（UTC）
 * @param releasedBy             放行人，未放行为 null
 * @param releasedAt             放行时间（UTC），未放行为 null
 * @param frozenReferenceValue   放行时冻结的参考校正值（十进制字符串），未放行为 null
 * @param frozenDutValue         放行时冻结的待测校正值（十进制字符串），未放行为 null
 * @param cancelledBy            取消人，未取消为 null
 * @param cancelledAt            取消时间（UTC），未取消为 null
 */
public record DifferentialResponse(
        long id,
        String differentialKey,
        String instrumentId,
        String referenceMeasurementKey,
        String dutMeasurementKey,
        long referenceCertificateId,
        long dutCertificateId,
        String difference,
        String displayDifference,
        String lowerLimit,
        String upperLimit,
        boolean passed,
        String status,
        boolean usable,
        long version,
        String createdBy,
        Instant createdAt,
        String releasedBy,
        Instant releasedAt,
        String frozenReferenceValue,
        String frozenDutValue,
        String cancelledBy,
        Instant cancelledAt) {
}
