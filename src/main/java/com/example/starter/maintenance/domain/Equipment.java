package com.example.starter.maintenance.domain;

/**
 * 设备。
 *
 * @param equipmentId               设备唯一标识
 * @param maintenancePeriodMinutes  保养周期（分钟），登记后不可修改
 * @param meterRangeMinutes         工时表量程 M（分钟），正整数；null 表示未启用量程；
 *                                  仅可在尚无读数时登记，登记后不可变
 * @param version                   设备版本号，初始 1，每次写操作校验并加一
 */
public record Equipment(String equipmentId, long maintenancePeriodMinutes,
                        Long meterRangeMinutes, long version) {
}
