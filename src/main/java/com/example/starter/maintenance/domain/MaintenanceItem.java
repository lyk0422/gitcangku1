package com.example.starter.maintenance.domain;

/**
 * 保养项目。每台设备的 DEFAULT 项目迁移自登记时的原保养周期；
 * 其余项目由新增接口创建，创建后不可修改或删除。
 *
 * @param equipmentId               所属设备标识
 * @param itemCode                  项目编码，设备内唯一；DEFAULT 为默认项目
 * @param maintenancePeriodMinutes  该项目独立保养周期（分钟），正整数
 * @param createdAt                 项目创建时刻（UTC）
 */
public record MaintenanceItem(
        String equipmentId,
        String itemCode,
        long maintenancePeriodMinutes,
        java.time.Instant createdAt) {

    /** 登记设备时迁移生成的默认保养项目编码。 */
    public static final String DEFAULT = "DEFAULT";
}
