package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 设备日历档案视图。
 *
 * @param equipmentId       设备唯一标识
 * @param commissionedAt    UTC 投用时刻
 * @param periodDays        日历保养周期（天）
 * @param operator          启用日历的操作者
 * @param equipmentVersion  操作后的设备版本号
 */
public record CalendarResponse(
        String equipmentId,
        Instant commissionedAt,
        int periodDays,
        String operator,
        long equipmentVersion) {
}
