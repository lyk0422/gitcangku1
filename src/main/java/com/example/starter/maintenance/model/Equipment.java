package com.example.starter.maintenance.model;

/**
 * 设备：保养周期登记后不可改；version 为乐观锁版本，每次成功写操作加一。
 *
 * @param equipmentId               设备唯一标识
 * @param maintenanceIntervalMinutes 保养周期，单位分钟，正整数
 * @param version                   设备版本，初始1
 */
public record Equipment(String equipmentId, int maintenanceIntervalMinutes, int version) {
}
