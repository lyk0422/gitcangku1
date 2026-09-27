package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 双人完工确认（不可变）。确认人不得为证据包提交人；确认即原子完成保养并重置下一周期。
 *
 * @param confirmationId  完工确认自增主键
 * @param equipmentId     所属设备标识
 * @param cycleNo         完成的保养周期序号
 * @param packageVersion  被确认的证据包版本号
 * @param templateVersion 确认时的模板版本号（快照）
 * @param confirmedBy     确认人（另一维护角色，不得为提交人）
 * @param maintenanceId   原子生成的保养记录标识
 * @param confirmedAt     确认时刻（UTC）
 */
public record CompletionConfirmation(long confirmationId, String equipmentId, long cycleNo,
                                     int packageVersion, int templateVersion, String confirmedBy,
                                     long maintenanceId, Instant confirmedAt) {
}
