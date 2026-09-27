package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 保养更正附录（只追加）。完工后发现错误只能创建附录，不能重开周期或覆盖工时历史。
 *
 * @param correctionId  附录主键
 * @param maintenanceId 关联的已完成保养记录标识
 * @param equipmentId   所属设备标识
 * @param cycleNo       关联周期号
 * @param content       更正内容（实际值、要求值或差额等说明）
 * @param createdBy     附录创建人
 * @param requestId     创建附录请求的 requestId
 * @param createdAt     附录创建时刻（UTC）
 */
public record MaintenanceCorrection(
        long correctionId,
        long maintenanceId,
        String equipmentId,
        int cycleNo,
        String content,
        String createdBy,
        String requestId,
        Instant createdAt) {
}
