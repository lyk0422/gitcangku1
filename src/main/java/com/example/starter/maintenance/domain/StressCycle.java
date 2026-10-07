package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 闭合应力循环（账本记录，仅完整闭合循环落库）。损伤 = (riseStressMpa - 基准应力)^3。
 *
 * @param equipmentId            所属设备标识
 * @param cycleId                循环标识，设备内唯一
 * @param operator               操作者标识，非空
 * @param riseStressMpa          升峰采样应力（兆帕），大于基准应力
 * @param riseSampledAt          升峰采样 UTC 时刻
 * @param riseReadingId          升峰采样引用的工时读数标识
 * @param riseReadingRevisionNo  升峰引用读数在登记时的现存修订号
 * @param fallStressMpa          回落采样应力（兆帕），等于基准应力
 * @param fallSampledAt          回落采样 UTC 时刻
 * @param fallReadingId          回落采样引用的工时读数标识
 * @param fallReadingRevisionNo  回落引用读数在登记时的现存修订号
 * @param damage                 本循环损伤量（精确整数）
 */
public record StressCycle(
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
        long damage) {
}
