package com.example.starter.maintenance.domain;

/**
 * 设备。
 *
 * @param equipmentId               设备唯一标识
 * @param maintenancePeriodMinutes  物理保养周期（分钟），登记后不可修改
 * @param equivalentPeriodMinutes   等效寿命周期（等效分钟）；null 表示未启用等效寿命口径
 * @param version                   设备版本号，初始 1，每次写操作校验并加一
 */
public record Equipment(String equipmentId, long maintenancePeriodMinutes,
                        Long equivalentPeriodMinutes, long version) {
}
