package com.example.starter.evidence.dto;

import com.example.starter.evidence.ResealStatus;

import java.time.LocalDateTime;

/**
 * 重新封存申请视图（申请历史条目与确认/撤销响应）。
 * 快照记录申请人、见证人、前后封条及 UTC 时刻。
 *
 * @param resealKey      重新封存业务键
 * @param evidenceKey    关联证物业务键
 * @param applicantId    申请人（申请时的当前保管人）
 * @param witnessId      指定见证人
 * @param previousSealNo 确认前封条号
 * @param newSealNo      确认后启用的新封条号
 * @param reason         重新封存原因
 * @param status         申请状态
 * @param createdAt      申请时刻（UTC）
 * @param decidedAt      确认或撤销时刻（UTC）；null 表示待确认
 */
public record ResealView(
        String resealKey,
        String evidenceKey,
        String applicantId,
        String witnessId,
        String previousSealNo,
        String newSealNo,
        String reason,
        ResealStatus status,
        LocalDateTime createdAt,
        LocalDateTime decidedAt) {
}
