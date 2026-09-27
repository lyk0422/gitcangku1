package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 双人完工确认（与保养记录 1:1，原子写入，不可变）。
 *
 * @param maintenanceId    对应保养记录主键
 * @param equipmentId      所属设备标识
 * @param cycleNo          被确认的保养周期序号
 * @param templateVersion  固化的模板版本
 * @param packageVersion   被确认的证据包版本
 * @param submitterId      证据包提交人
 * @param confirmerId      完工确认人（另一维护人员，不同于提交人）
 * @param confirmedAt      确认与完工时刻（UTC）
 */
public record MaintenanceConfirmation(
        long maintenanceId,
        String equipmentId,
        int cycleNo,
        int templateVersion,
        int packageVersion,
        String submitterId,
        String confirmerId,
        Instant confirmedAt) {
}
