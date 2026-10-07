package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 闭合应力循环视图。
 *
 * @param equipmentId            所属设备标识
 * @param cycleId                循环标识
 * @param operator               操作者标识
 * @param riseStressMpa          升峰采样应力（兆帕）
 * @param riseSampledAt          升峰采样 UTC 时刻
 * @param riseReadingId          升峰引用的工时读数标识
 * @param riseReadingRevisionNo  升峰引用读数的修订号
 * @param fallStressMpa          回落采样应力（兆帕）
 * @param fallSampledAt          回落采样 UTC 时刻
 * @param fallReadingId          回落引用的工时读数标识
 * @param fallReadingRevisionNo  回落引用读数的修订号
 * @param damage                 本循环损伤量（(峰值-基准)^3，精确整数）
 * @param currentRound           是否归属当前轮（false 表示归历史轮，不给当前轮加量）
 * @param equipmentVersion       操作后的设备版本号（仅写操作响应中有意义，查询时为当前版本）
 */
public record CycleResponse(
        String equipmentId,
        String cycleId,
        String operator,
        long riseStressMpa,
        Instant riseSampledAt,
        String riseReadingId,
        int riseReadingRevisionNo,
        long fallStressMpa,
        Instant fallSampledAt,
        String fallReadingId,
        int fallReadingRevisionNo,
        long damage,
        boolean currentRound,
        long equipmentVersion) {
}
