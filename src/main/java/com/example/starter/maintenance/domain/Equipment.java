package com.example.starter.maintenance.domain;

/**
 * 设备。
 *
 * @param equipmentId               设备唯一标识
 * @param maintenancePeriodMinutes  保养周期（分钟），登记后不可修改
 * @param fatigueThreshold          疲劳阈值（正整数），本轮累计损伤达到即 DUE；null 表示未启用疲劳门禁
 * @param baselineStressMpa         基准应力（兆帕），非负整数；null 表示未配置，不能写入循环
 * @param version                   设备版本号，初始 1，每次写操作校验并加一
 */
public record Equipment(String equipmentId, long maintenancePeriodMinutes,
                        Long fatigueThreshold, Long baselineStressMpa, long version) {
}
