package com.example.starter.observation;

import java.time.Instant;

/**
 * 采样许可响应：返回许可的授权范围、状态与计数。
 *
 * @param permitId        许可唯一标识
 * @param deviceId        被授权设备标识
 * @param permitVersion   许可版本（不可变）
 * @param gridMin         授权网格区间下界（闭区间）
 * @param gridMax         授权网格区间上界（闭区间）
 * @param validFromUtc    有效期起点（UTC，含）
 * @param validToUtc      原定有效期终点（UTC，不含）
 * @param terminatedAtUtc 提前结束时刻（UTC，不含）；null 表示未提前结束
 * @param effectiveValidToUtc 实际有效上界（UTC，不含）：提前结束时刻与原定终点的较早者
 * @param revokedAtUtc    撤销时刻（UTC）；null 表示未撤销
 * @param status          许可状态 ACTIVE/REVOKED
 * @param maxObservations 最大观测数（单位：条）
 * @param observedCount   已接收观测计数（单位：条）
 * @param remaining       剩余可接收观测数（单位：条，不为负）
 */
public record PermitResponse(
        String permitId,
        String deviceId,
        String permitVersion,
        int gridMin,
        int gridMax,
        Instant validFromUtc,
        Instant validToUtc,
        Instant terminatedAtUtc,
        Instant effectiveValidToUtc,
        Instant revokedAtUtc,
        String status,
        int maxObservations,
        int observedCount,
        int remaining) {

    public static PermitResponse of(SamplingPermit permit) {
        return new PermitResponse(
                permit.permitId(),
                permit.deviceId(),
                permit.permitVersion(),
                permit.gridMin(),
                permit.gridMax(),
                permit.validFromUtc(),
                permit.validToUtc(),
                permit.terminatedAtUtc(),
                permit.effectiveValidToUtc(),
                permit.revokedAtUtc(),
                permit.status().name(),
                permit.maxObservations(),
                permit.observedCount(),
                Math.max(0, permit.maxObservations() - permit.observedCount()));
    }
}
