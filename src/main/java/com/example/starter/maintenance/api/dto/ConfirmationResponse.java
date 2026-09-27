package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 双人完工确认视图。
 *
 * @param confirmationId   完工确认标识
 * @param maintenanceId    原子生成的保养记录标识
 * @param equipmentId      所属设备标识
 * @param cycleNo          完成的保养周期序号
 * @param nextCycleNo      重置后的下一保养周期序号
 * @param packageVersion   被确认的证据包版本号
 * @param templateVersion  确认时的模板版本号（快照）
 * @param confirmedBy      确认人
 * @param confirmedAt      确认时刻（UTC）
 * @param equipmentVersion 确认后的设备版本号
 */
public record ConfirmationResponse(
        long confirmationId,
        long maintenanceId,
        String equipmentId,
        long cycleNo,
        long nextCycleNo,
        int packageVersion,
        int templateVersion,
        String confirmedBy,
        Instant confirmedAt,
        long equipmentVersion) {
}
