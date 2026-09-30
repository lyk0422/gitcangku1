package com.example.starter.maintenance.domain;

/**
 * 设备。
 *
 * @param equipmentId               设备唯一标识
 * @param maintenancePeriodMinutes  保养周期（分钟），登记后不可修改
 * @param revisionThresholdMinutes  大幅读数修订见证阈值（分钟），登记后不可修改；
 *                                  修订值与当前有效值差额绝对值超出该阈值时须另一维护人见证
 * @param version                   设备版本号，初始 1，每次写操作校验并加一
 */
public record Equipment(String equipmentId, long maintenancePeriodMinutes,
                        long revisionThresholdMinutes, long version) {
}
