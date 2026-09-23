package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 保养项目视图。
 *
 * @param equipmentId               所属设备标识
 * @param itemCode                  项目编码，设备内唯一；DEFAULT 为登记时原周期项目
 * @param maintenancePeriodMinutes  该项目独立保养周期（分钟），创建后不可修改
 * @param createdAt                 项目创建时刻（UTC）
 * @param equipmentVersion          操作后的设备版本号（新增项目响应）；列表查询时为当前版本
 */
public record MaintenanceItemResponse(
        String equipmentId,
        String itemCode,
        long maintenancePeriodMinutes,
        Instant createdAt,
        long equipmentVersion) {
}
