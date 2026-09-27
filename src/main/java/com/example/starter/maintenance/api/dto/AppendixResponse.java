package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 保养更正附录视图（不可变）。
 *
 * @param appendixId    附录标识
 * @param maintenanceId 所属保养记录标识
 * @param equipmentId   所属设备标识
 * @param createdBy     附录创建人
 * @param content       更正内容说明
 * @param createdAt     创建时刻（UTC）
 */
public record AppendixResponse(
        long appendixId,
        long maintenanceId,
        String equipmentId,
        String createdBy,
        String content,
        Instant createdAt) {
}
