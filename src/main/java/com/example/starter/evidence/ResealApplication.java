package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 异常证物双人重新封存申请实体，对应 reseal_application 表。记录只追加；
 * 申请（PENDING）不改变证物当前封条与异常状态；确认后仅由条件更新进入终态，
 * 证物同事务恢复 SEALED 并换用新封条，撤销不换封条。
 *
 * @param id             主键
 * @param resealKey      重新封存业务键，全局唯一；换命令键复用该键返回 409
 * @param evidenceKey    关联证物业务键
 * @param applicantId    申请人（申请时的当前保管人），只有其可撤销
 * @param witnessId      指定见证人，必须与申请人不同，只有其可确认
 * @param previousSealNo 申请时（确认前）的封条号
 * @param newSealNo      新封条号，不得与本证物任一历史封条相同
 * @param reason         重新封存原因，非空
 * @param status         申请状态：PENDING / CONFIRMED / CANCELLED
 * @param createdAt      申请时刻（UTC）
 * @param decidedAt      确认或撤销时刻（UTC）；NULL 表示待确认
 */
public record ResealApplication(
        Long id,
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
