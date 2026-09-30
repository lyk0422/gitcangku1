package com.example.starter.billing.domain;

import java.time.Instant;

/**
 * 削峰邀约：调度方发布的一个左闭右开 UTC 时段。
 *
 * @param id        邀约 ID（调度方事件标识，全局唯一）
 * @param startTime 时段起点（UTC，含）
 * @param endTime   时段终点（UTC，不含）
 * @param createdAt 创建时间（UTC）
 */
public record DemandOffer(
        String id,
        Instant startTime,
        Instant endTime,
        Instant createdAt) {

    /**
     * 判断给定瞬时是否已超过时段终点。
     *
     * @param now 待判断的瞬时（UTC）
     * @return 时段已结束返回 true
     */
    public boolean isFinishedAt(Instant now) {
        return !now.isBefore(endTime);
    }

    /**
     * 判断给定左闭右开时段是否与邀约时段完全一致。
     *
     * @param start 待判断时段起点（含）
     * @param end   待判断时段终点（不含）
     * @return 完全一致返回 true
     */
    public boolean coversExactly(java.time.Instant start, java.time.Instant end) {
        return startTime.equals(start) && endTime.equals(end);
    }
}
