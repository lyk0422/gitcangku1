package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 闭合应力循环视图。
 *
 * @param equipmentId       所属设备标识
 * @param cycleId           循环标识
 * @param fromReadingId     起始引用工时读数标识
 * @param fromRevisionNo    起始引用读数在写入时的修订号
 * @param toReadingId       结束引用工时读数标识
 * @param toRevisionNo      结束引用读数在写入时的修订号
 * @param peakAt            升峰采样时刻（UTC）
 * @param returnAt          回落采样时刻（UTC）
 * @param peakStressMpa     峰值应力（兆帕）
 * @param returnStressMpa   回落应力（兆帕），等于基准应力
 * @param damage            本循环损伤 = (峰值应力 - 基准应力)^3（精确整数）
 * @param operator          操作者
 * @param equipmentVersion  操作后的设备版本号（查询列表时为当前版本）
 */
public record CycleResponse(
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
        String operator,
        long equipmentVersion) {
}
