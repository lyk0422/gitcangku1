package com.example.starter.maintenance.domain;

/**
 * 设备。
 *
 * @param equipmentId               设备唯一标识
 * @param maintenancePeriodMinutes  保养周期（分钟），登记后不可修改
 * @param overdueThresholdMinutes   逾期阈值（分钟），可空；非空时大于保养周期，达到为 OVERDUE，null 表示不判定
 * @param version                   设备版本号，初始 1，每次写操作校验并加一
 */
public record Equipment(String equipmentId, long maintenancePeriodMinutes,
                        Long overdueThresholdMinutes, long version) {
}
