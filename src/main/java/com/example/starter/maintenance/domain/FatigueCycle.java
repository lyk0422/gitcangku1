package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 闭合应力循环（账本条目，不允许删除）。损伤 = (峰值应力 - 基准应力)^3，精确整数。
 *
 * @param equipmentId      所属设备标识
 * @param cycleId          循环标识，设备内唯一
 * @param fromReadingId    起始引用工时读数标识
 * @param fromRevisionNo   起始引用读数在写入时的修订号（快照）
 * @param toReadingId      结束引用工时读数标识
 * @param toRevisionNo     结束引用读数在写入时的修订号（快照）
 * @param peakAt           升峰采样时刻（UTC）
 * @param returnAt         回落采样时刻（UTC），严格晚于升峰时刻
 * @param peakStressMpa    峰值应力（兆帕），大于基准应力
 * @param returnStressMpa  回落应力（兆帕），等于基准应力
 * @param damage           本循环损伤（精确整数）
 * @param operator         操作者（非空）
 */
public record FatigueCycle(
        String equipmentId,
        String cycleId,
        String fromReadingId,
        int fromRevisionNo,
        String toReadingId,
        int toRevisionNo,
        Instant peakAt,
        Instant returnAt,
        long peakStressMpa,
        long returnStressMpa,
        long damage,
        String operator) {
}
