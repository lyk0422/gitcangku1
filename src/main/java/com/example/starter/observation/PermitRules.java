package com.example.starter.observation;

import java.time.Instant;

/**
 * 采样许可纯规则：网格与 UTC 左闭右开时间窗的覆盖、相交与命中判定，不访问数据库与时钟，可独立单测。
 */
public final class PermitRules {

    private PermitRules() {
    }

    /**
     * 整数闭区间网格是否相交（端点相接也算相交：网格单元为离散编号，共享单元即重叠）。
     */
    public static boolean gridIntersects(int minA, int maxA, int minB, int maxB) {
        return minA <= maxB && minB <= maxA;
    }

    /**
     * 两个左闭右开时间窗是否相交：共享任意时刻即重叠；{@code [a,b)} 与 {@code [b,c)} 仅在端点相接，不相交。
     */
    public static boolean timeIntersects(Instant fromA, Instant toA, Instant fromB, Instant toB) {
        return fromA.isBefore(toB) && fromB.isBefore(toA);
    }

    /**
     * 两张许可的“有效”授权区域是否在网格与时间两个维度同时相交（同设备重叠检测）。
     * 以创建参数的原定窗口与网格区间比较：提前结束只缩小窗口，许可创建时均无提前结束。
     */
    public static boolean overlaps(SamplingPermit existing, int gridMin, int gridMax,
                                   Instant validFromUtc, Instant validToUtc) {
        if (existing.status() != PermitStatus.ACTIVE) {
            return false;
        }
        return gridIntersects(existing.gridMin(), existing.gridMax(), gridMin, gridMax)
                && timeIntersects(existing.validFromUtc(), existing.effectiveValidToUtc(),
                validFromUtc, validToUtc);
    }

    /**
     * 许可是否命中给定采样点：状态 ACTIVE、网格落在闭区间、时刻落在左闭右开窗口。
     */
    public static boolean matches(SamplingPermit permit, String deviceId, int gridId, Instant observedAtUtc) {
        return permit.status() == PermitStatus.ACTIVE
                && permit.deviceId().equals(deviceId)
                && permit.coversGrid(gridId)
                && permit.covers(observedAtUtc);
    }

    /**
     * 许可创建参数的时间窗是否合法：起点必须早于终点，且起点不得早于当前时刻（禁止追溯许可）。
     */
    public static boolean isValidCreateRange(Instant validFromUtc, Instant validToUtc, Instant nowUtc) {
        return validFromUtc != null && validToUtc != null
                && validFromUtc.isBefore(validToUtc)
                && !validFromUtc.isBefore(nowUtc);
    }
}
