package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 保养项目。每台设备登记时迁移生成 itemCode=DEFAULT 的项目（周期为设备登记周期），
 * 之后可新增项目；项目周期创建后不可修改，项目不可删除。
 *
 * @param equipmentId               所属设备标识
 * @param itemCode                  项目编码，设备内唯一；DEFAULT 为登记时原周期项目
 * @param maintenancePeriodMinutes  该项目独立保养周期（分钟），正整数
 * @param createdAt                 项目创建时刻（UTC）
 */
public record MaintenanceItem(
        String equipmentId,
        String itemCode,
        long maintenancePeriodMinutes,
        Instant createdAt) {

    /** 登记设备时迁移生成的原保养项目编码。 */
    public static final String DEFAULT_ITEM_CODE = "DEFAULT";

    /** 每台设备允许的保养项目数量上限（含 DEFAULT）。 */
    public static final int MAX_ITEMS_PER_EQUIPMENT = 21;
}
