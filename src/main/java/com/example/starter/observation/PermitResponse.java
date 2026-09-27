package com.example.starter.observation;

import java.time.Instant;

/**
 * 采样许可响应：包含许可范围、有效期、版本与观测计数（已用/上限/剩余）。
 *
 * @param permitId        采样许可唯一标识
 * @param deviceId        许可绑定的采集设备标识
 * @param gridXMin        网格 X 坐标下限（含）
 * @param gridXMax        网格 X 坐标上限（含）
 * @param gridYMin        网格 Y 坐标下限（含）
 * @param gridYMax        网格 Y 坐标上限（含）
 * @param validFromUtc    有效期起始时刻（UTC，含）
 * @param validToUtc      有效期结束时刻（UTC，不含）
 * @param maxObservations 许可允许的最大观测数
 * @param usedCount       已扣减的观测计数
 * @param remainingCount  剩余可扣减计数（maxObservations - usedCount）
 * @param permitVersion   许可版本（permitVersion）
 * @param status          许可状态：ACTIVE / ENDED / REVOKED
 */
public record PermitResponse(
        String permitId,
        String deviceId,
        int gridXMin,
        int gridXMax,
        int gridYMin,
        int gridYMax,
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
                permit.gridXMin(), permit.gridXMax(), permit.gridYMin(), permit.gridYMax(),
                permit.validFromUtc(), permit.validToUtc(),
                permit.maxObservations(), permit.usedCount(),
                permit.maxObservations() - permit.usedCount(),
                permit.permitVersion(), permit.status());
    }
}
