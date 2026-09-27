package com.example.starter.maintenance.domain;

/**
 * 设备。
 *
 * @param equipmentId               设备唯一标识
 * @param maintenancePeriodMinutes  保养周期（分钟），登记后不可修改
 * @param version                   设备版本号，初始 1，每次写操作校验并加一
 * @param templateId                当前保养模板标识；null 表示未分配模板（沿用无证据包流程）
 * @param currentCycleNo            当前保养周期序号，初始 1，双人确认完工后加一
 */
public record Equipment(String equipmentId, long maintenancePeriodMinutes, long version,
                        String templateId, long currentCycleNo) {
}
