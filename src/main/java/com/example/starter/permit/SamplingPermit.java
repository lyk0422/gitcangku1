package com.example.starter.permit;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 采样许可实体：对应 sampling_permit 表一行。
 *
 * <p>有效期为 UTC 左闭右开：采样时刻 t 命中时间条件当且仅当
 * {@code validFrom <= t < min(validTo, effectiveEnd)}，effectiveEnd 为空时按自然到期。
 *
 * @param permitId        许可唯一标识
 * @param deviceId        绑定设备标识
 * @param permitVersion   许可版本号（同设备唯一，参与许可指纹）
 * @param gridMinLon      许可网格最小经度（含边界）
 * @param gridMinLat      许可网格最小纬度（含边界）
 * @param gridMaxLon      许可网格最大经度（含边界）
 * @param gridMaxLat      许可网格最大纬度（含边界）
 * @param validFrom       有效期开始时刻（UTC，左闭）
 * @param validTo         有效期结束时刻（UTC，右开）
 * @param effectiveEnd    提前结束/撤销实际生效时刻（UTC，右开）；null 表示未提前终止
 * @param maxObservations 最大观测数
 * @param acceptedCount   已接收计数（含撤销后进入复核的观测）
 * @param status          ACTIVE / ENDED / REVOKED
 * @param revokeSnapshot  撤销时未合并观测快照 JSON；未撤销为 null
 * @param revokedAt       撤销完成时刻（UTC）；未撤销为 null
 */
public record SamplingPermit(
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
        String status,
        String revokeSnapshot,
        Instant revokedAt) {

    public static final String ACTIVE = "ACTIVE";
    public static final String ENDED = "ENDED";
    public static final String REVOKED = "REVOKED";

    /**
     * 许可在指定采样时刻是否时间有效（左闭右开，含提前结束截断）。
     */
    public boolean timeCovers(Instant sampledAt) {
        Instant end = effectiveEnd != null && effectiveEnd.isBefore(validTo) ? effectiveEnd : validTo;
        return !sampledAt.isBefore(validFrom) && sampledAt.isBefore(end);
    }

    /**
     * 采集网格点是否落在许可网格闭矩形内（含边界）。
     */
    public boolean gridCovers(BigDecimal lon, BigDecimal lat) {
        return lon.compareTo(gridMinLon) >= 0 && lon.compareTo(gridMaxLon) <= 0
                && lat.compareTo(gridMinLat) >= 0 && lat.compareTo(gridMaxLat) <= 0;
    }

    /**
     * 与另一同设备许可在网格与时间上是否存在非空交集（时间按左闭右开、网格闭矩形）。
     */
    public boolean overlaps(SamplingPermit other) {
        boolean timeOverlap = validFrom.isBefore(other.timeEnd()) && other.validFrom().isBefore(timeEnd());
        boolean gridOverlap = gridMinLon.compareTo(other.gridMaxLon) <= 0
                && other.gridMinLon.compareTo(gridMaxLon) <= 0
                && gridMinLat.compareTo(other.gridMaxLat) <= 0
                && other.gridMinLat.compareTo(gridMaxLat) <= 0;
        return timeOverlap && gridOverlap;
    }

    /**
     * 该许可当前实际生效窗口的右端点（右开）。
     */
    public Instant timeEnd() {
        return effectiveEnd != null && effectiveEnd.isBefore(validTo) ? effectiveEnd : validTo;
    }
}
