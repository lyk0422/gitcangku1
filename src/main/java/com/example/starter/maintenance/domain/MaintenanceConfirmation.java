package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 双人完工确认（不可变）。确认与保养落库、周期推进在同一事务原子完成。
 *
 * @param confirmationId  确认记录主键
 * @param equipmentId     所属设备标识
 * @param cycleNo         被确认完工的周期号
 * @param packageVersion  确认时指向的证据包版本
 * @param templateVersion 确认时证据包固化的模板版本
 * @param maintenanceId   同事务生成的保养记录标识
 * @param submittedBy     证据提交人（固化）
 * @param confirmedBy     完工确认人（另一维护角色，固化）
 * @param requestId       完工确认请求 requestId
 * @param confirmedAt     确认与完工时刻（UTC）
 */
public record MaintenanceConfirmation(
        long confirmationId,
        String equipmentId,
        int cycleNo,
        int packageVersion,
        int templateVersion,
        long maintenanceId,
        String submittedBy,
        String confirmedBy,
        String requestId,
        Instant confirmedAt) {
}
