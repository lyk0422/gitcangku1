package com.example.starter.observation;

import java.time.Instant;

/**
 * 采样许可领域记录：对应 sampling_permit 表的一行。
 *
 * <p>许可授权一台设备在一片连续整数网格区间 {@code [gridMin, gridMax]} 与
 * 一个 UTC 左闭右开时间窗 {@code [validFromUtc, validToUtc)} 内采样至多 {@code maxObservations} 条。
 * 提前结束仅把有效窗口上界改为 {@code terminatedAtUtc}（不含该时刻），不改变其余字段。
 *
 * @param permitId       许可唯一标识
 * @param deviceId       被授权设备标识
 * @param permitVersion  许可版本，创建后不可变
 * @param gridMin        授权网格区间下界（闭区间，单位：网格单元编号）
 * @param gridMax        授权网格区间上界（闭区间，单位：网格单元编号）
 * @param validFromUtc   有效期起点（UTC，含）
 * @param validToUtc     有效期终点（UTC，不含）
 * @param terminatedAtUtc 提前结束时刻（UTC，不含）；null 表示未提前结束
 * @param revokedAtUtc   撤销时刻（UTC）；null 表示未撤销
 * @param status         许可状态
 * @param maxObservations 最大观测数（单位：条）
 * @param observedCount  已接收观测计数（单位：条）
 */
public record SamplingPermit(
        String permitId,
        String deviceId,
        String permitVersion,
        int gridMin,
        int gridMax,
        Instant validFromUtc,
        Instant validToUtc,
        Instant terminatedAtUtc,
        Instant revokedAtUtc,
        PermitStatus status,
        int maxObservations,
        int observedCount) {

    /**
     * 当前实际有效窗口上界（UTC，不含）：取提前结束时刻与原定终点的较早者。
     */
    public Instant effectiveValidToUtc() {
        return terminatedAtUtc == null || terminatedAtUtc.isAfter(validToUtc)
                ? validToUtc
                : terminatedAtUtc;
    }

    /**
     * 许可是否可能在给定时刻命中：状态为 ACTIVE 且时刻落在左闭右开有效窗口内。
     */
    public boolean covers(Instant atUtc) {
        return status == PermitStatus.ACTIVE
                && !atUtc.isBefore(validFromUtc)
                && atUtc.isBefore(effectiveValidToUtc());
    }

    /**
     * 许可是否覆盖给定网格单元（闭区间）。
     */
    public boolean coversGrid(int gridId) {
        return gridId >= gridMin && gridId <= gridMax;
    }

    /**
     * 是否完整覆盖目标网格区间（批量坐标边界预校验使用）。
     */
    public boolean coversGridRange(int minGridId, int maxGridId) {
        return minGridId >= gridMin && maxGridId <= gridMax;
    }
}
