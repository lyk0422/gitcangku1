package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 保养延期记录。申请时固化申请人工时快照；批准时固化原/新阈值，之后不可变。
 * 仅作用于申请时所处的保养周期（cycleMaintenanceId），保养完成后周期额度归零。
 *
 * @param deferralId               延期记录自增主键
 * @param equipmentId              所属设备标识
 * @param deferKey                 延期申请标识，设备内唯一
 * @param cycleMaintenanceId       申请时所处保养周期标识（最近保养记录主键，无保养为 0）
 * @param requestedMinutes         申请延期分钟数（1～10080，且不超过保养周期 25%）
 * @param reason                   申请原因
 * @param status                   PENDING / APPROVED / REJECTED / EXPIRED
 * @param applicant                申请人标识
 * @param approver                 审批人标识（批准或拒绝的操作人），待审批时为 null
 * @param rejectReason             拒绝理由，仅 REJECTED 时有值
 * @param appliedRunMinutes        申请时刻本轮运行分钟
 * @param originalThresholdMinutes 批准时原阈值（分钟），待审批/已拒绝时为 null
 * @param newThresholdMinutes      批准后新阈值（分钟），待审批/已拒绝时为 null
 * @param createdAt                申请时刻（UTC）
 * @param decidedAt                审批/失效时刻（UTC），待审批时为 null
 */
public record Deferral(
        long deferralId,
        String equipmentId,
        String deferKey,
        long cycleMaintenanceId,
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

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_EXPIRED = "EXPIRED";
}
