package com.example.starter.sampling;

import java.time.Instant;

/**
 * 采样许可响应：携带许可配置与实时计数。
 *
 * @param permitId        许可唯一标识
 * @param deviceId        观测设备标识
 * @param gridMinX        网格范围最小X（含）
 * @param gridMaxX        网格范围最大X（含）
 * @param gridMinY        网格范围最小Y（含）
 * @param gridMaxY        网格范围最大Y（含）
 * @param validFromUtc    有效期起点（UTC，左闭）
 * @param validToUtc      有效期终点（UTC，右开）
 * @param maxObservations 最大观测数上限
 * @param usedCount       已接收观测计数
 * @param remainingCount  剩余可接收观测数
 * @param permitVersion   许可版本号
 * @param status          许可状态：ACTIVE / ENDED / REVOKED
 */
public record PermitResponse(
        String permitId,
        String deviceId,
        int gridMinX,
        int gridMaxX,
        int gridMinY,
        int gridMaxY,
        Instant validFromUtc,
        Instant validToUtc,
        int maxObservations,
        int usedCount,
        int remainingCount,
        int permitVersion,
        String status) {

    /**
     * 由许可记录构造响应。
     */
    public static PermitResponse of(SamplingPermit permit) {
        return new PermitResponse(permit.permitId(), permit.deviceId(),
                permit.gridMinX(), permit.gridMaxX(), permit.gridMinY(), permit.gridMaxY(),
                permit.validFromUtc(), permit.validToUtc(),
                permit.maxObservations(), permit.usedCount(),
                permit.maxObservations() - permit.usedCount(),
                permit.permitVersion(), permit.status().name());
    }
}
