package com.example.starter.maintenance.api.dto;

/**
 * 设备视图。
 *
 * @param equipmentId               设备唯一标识
 * @param maintenancePeriodMinutes  保养周期（分钟），登记后不可修改
 * @param fatigueThreshold          疲劳阈值（未配置为 null）
 * @param baselineStressMpa         基准应力（兆帕，未配置为 null）
 * @param version                   设备当前版本号，初始 1
 */
public record EquipmentResponse(
        String equipmentId,
        long maintenancePeriodMinutes,
        Long fatigueThreshold,
        Long baselineStressMpa,
        long version) {
}
