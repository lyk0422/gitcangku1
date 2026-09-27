package com.example.starter.permit;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 采样许可响应：含网格、有效期、状态与配额计数（used/remaining 便于查询许可计数）。
 *
 * @param permitId        许可唯一标识
 * @param deviceId        绑定设备标识
 * @param permitVersion   许可版本号
 * @param gridMinLon      网格最小经度
 * @param gridMinLat      网格最小纬度
 * @param gridMaxLon      网格最大经度
 * @param gridMaxLat      网格最大纬度
 * @param validFrom       有效期开始时刻（UTC，左闭）
 * @param validTo         有效期自然结束时刻（UTC，右开）
 * @param effectiveEnd    提前结束/撤销实际生效时刻（UTC，右开）；未提前终止为 null
 * @param maxObservations 最大观测数
 * @param acceptedCount   已接收计数
 * @param remaining       剩余可用配额（max - accepted，不为负）
 * @param status          ACTIVE / ENDED / REVOKED
 */
public record PermitResponse(
        String permitId,
        String deviceId,
        int permitVersion,
        BigDecimal gridMinLon,
        BigDecimal gridMinLat,
        BigDecimal gridMaxLon,
        BigDecimal gridMaxLat,
        Instant validFrom,
        Instant validTo,
        Instant effectiveEnd,
        int maxObservations,
        int acceptedCount,
        int remaining,
        String status) {

    public static PermitResponse of(SamplingPermit permit) {
        return new PermitResponse(
                permit.permitId(), permit.deviceId(), permit.permitVersion(),
                permit.gridMinLon(), permit.gridMinLat(), permit.gridMaxLon(), permit.gridMaxLat(),
                permit.validFrom(), permit.validTo(), permit.effectiveEnd(),
                permit.maxObservations(), permit.acceptedCount(),
                Math.max(0, permit.maxObservations() - permit.acceptedCount()), permit.status());
    }
}
