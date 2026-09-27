package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 设备。
 *
 * @param equipmentId               设备唯一标识
 * @param maintenancePeriodMinutes  保养周期（分钟），登记后不可修改
 * @param version                   设备版本号，初始 1，每次写操作校验并加一（锁定点确认除外）
 * @param status                    设备状态：ACTIVE 在用 / DEACTIVATED 已停用
 * @param deactivatedAt             停用时刻（UTC）；在用设备为 null
 */
public record Equipment(
        String equipmentId,
        long maintenancePeriodMinutes,
        long version,
        String status,
        Instant deactivatedAt) {

    public static final String ACTIVE = "ACTIVE";
    public static final String DEACTIVATED = "DEACTIVATED";

    public boolean active() {
        return ACTIVE.equals(status);
    }
}
