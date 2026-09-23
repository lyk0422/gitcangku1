package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 双人重新封存申请实体，对应 reseal_application 表。记录只追加，状态仅可由 PENDING 决定为
 * CONFIRMED/CANCELLED 两个终态之一。确认快照包含申请人、见证人、前后封条及 UTC 决定时刻。
 *
 * @param id              主键
 * @param resealKey       重新封存业务键，全局唯一
 * @param evidenceKey     关联证物业务键
 * @param applicantId     申请人（申请时的当前保管人）
 * @param witnessId       指定见证人
 * @param reason          重新封存原因
 * @param newSealNo       拟换用的新封条号
 * @param status          申请状态
 * @param oldSealNo       确认快照：确认前旧封条号；null 表示尚未确认
 * @param confirmedSealNo 确认快照：确认后启用的新封条号；null 表示尚未确认
 * @param appliedAt       申请提交时间（Asia/Shanghai）
 * @param decidedAt       确认/撤销的 UTC 时刻；null 表示仍待见证
 */
public record ResealApplication(
        Long id,
        String resealKey,
        String evidenceKey,
        String applicantId,
        String witnessId,
        String reason,
        String newSealNo,
        ResealStatus status,
        String oldSealNo,
        String confirmedSealNo,
        LocalDateTime appliedAt,
        LocalDateTime decidedAt) {
}
