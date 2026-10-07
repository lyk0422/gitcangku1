package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 设备日历档案：首次启用时写入，之后不可修改；未启用的设备无此档案，只看工时。
 *
 * @param equipmentId    设备唯一标识
 * @param commissionedAt UTC 投用时刻，启用后不可修改
 * @param periodDays     日历保养周期（天），1 至 365 整数，启用后不可修改
 * @param operator       启用日历的操作者，非空
 * @param requestId      启用日历请求的 requestId
 * @param createdAt      日历档案启用时刻（UTC）
 */
public record EquipmentCalendar(
        String equipmentId,
        Instant commissionedAt,
        int periodDays,
        String operator,
        String requestId,
        Instant createdAt) {
}
