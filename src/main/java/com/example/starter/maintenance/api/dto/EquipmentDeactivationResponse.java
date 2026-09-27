package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 设备停用响应视图。
 *
 * @param equipmentId    设备标识
 * @param status         停用后设备状态，固定 DEACTIVATED
 * @param deactivatedAt  停用时刻（UTC）
 * @param version        停用后的设备版本号
 */
public record EquipmentDeactivationResponse(
        String equipmentId,
        String status,
        Instant deactivatedAt,
        long version) {
}
