package com.example.starter.observation;

import java.time.Instant;

/**
 * 观测采样许可：对应 sampling_permit 表的一行。
 * 许可限定设备、网格闭区间范围、UTC 左闭右开有效期与最大观测数。
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
 * @param permitVersion   许可版本（permitVersion），创建时指定，之后不变
 * @param status          许可状态：ACTIVE / ENDED / REVOKED
 */
public record SamplingPermit(
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
        int permitVersion,
        String status) {

    /** 许可状态：有效。 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 许可状态：已提前结束（仅影响之后采样，已接收观测保留）。 */
    public static final String STATUS_ENDED = "ENDED";
    /** 许可状态：已撤销（不再命中任何提交）。 */
    public static final String STATUS_REVOKED = "REVOKED";

    /** 复核标记：正常。 */
    public static final String REVIEW_NONE = "NONE";
    /** 复核标记：许可已撤销，待人工复核。 */
    public static final String REVIEW_PERMIT_REVIEW = "PERMIT_REVIEW";

    /**
     * 判断给定网格坐标与采集时刻（UTC）是否落在本许可的网格闭区间与左闭右开有效期内。
     */
    public boolean covers(int gridX, int gridY, Instant collectedAtUtc) {
        return gridX >= gridXMin && gridX <= gridXMax
                && gridY >= gridYMin && gridY <= gridYMax
                && !collectedAtUtc.isBefore(validFromUtc)
                && collectedAtUtc.isBefore(validToUtc);
    }

    /**
     * 判断本许可与另一许可在网格与时间的交集上是否重叠（用于创建许可时的 409 判定）。
     */
    public boolean intersects(SamplingPermit other) {
        boolean gridOverlap = gridXMin <= other.gridXMax && other.gridXMin <= gridXMax
                && gridYMin <= other.gridYMax && other.gridYMin <= gridYMax;
        boolean timeOverlap = validFromUtc.isBefore(other.validToUtc)
                && other.validFromUtc.isBefore(validToUtc);
        return gridOverlap && timeOverlap;
    }
}
