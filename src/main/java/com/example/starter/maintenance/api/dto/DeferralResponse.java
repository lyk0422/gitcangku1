package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 保养延期记录视图。批准后的记录不可变：固化申请时工时、原阈值、新阈值、双方操作人与原因。
 *
 * @param deferralId               延期记录主键
 * @param equipmentId              所属设备标识
 * @param deferKey                 延期申请标识
 * @param requestedMinutes         申请延期分钟数
 * @param reason                   申请原因
 * @param status                   PENDING / APPROVED / REJECTED / EXPIRED
 * @param applicant                申请人标识
 * @param approver                 审批人标识（待审批时为 null）
 * @param rejectReason             拒绝理由（仅 REJECTED 时有值）
 * @param appliedRunMinutes        申请时刻本轮运行分钟
 * @param originalThresholdMinutes 批准时原阈值（分钟）
 * @param newThresholdMinutes      批准后新阈值（分钟）
 * @param createdAt                申请时刻（UTC）
 * @param decidedAt                审批/失效时刻（UTC）
 */
public record DeferralResponse(
        long deferralId,
        String equipmentId,
        String deferKey,
        long requestedMinutes,
        String reason,
        String status,
        String applicant,
        String approver,
        String rejectReason,
        long appliedRunMinutes,
        Long originalThresholdMinutes,
        Long newThresholdMinutes,
        Instant createdAt,
        Instant decidedAt) {
}
