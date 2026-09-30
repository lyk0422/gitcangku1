package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 销毁申请记录，对应 destruction_record 表。只追加，冻结到期或解除均不改写历史记录。
 *
 * @param id              主键
 * @param evidenceKey     关联证物业务键
 * @param requestedBy     申请操作人（申请时的当前保管人）
 * @param reason          销毁原因
 * @param status          审批结果：APPROVED 已销毁 / REJECTED 被有效冻结阻断
 * @param rejectReason    阻断说明；NULL 表示申请通过
 * @param blockingHoldKey 阻断销毁的冻结业务键；NULL 表示申请通过
 * @param decidedAt       UTC 申请判定时刻
 * @param createdAt       记录创建时间（Asia/Shanghai）
 */
public record DestructionRecord(
        Long id,
        String evidenceKey,
        String requestedBy,
        String reason,
        DestructionStatus status,
        String rejectReason,
        String blockingHoldKey,
        LocalDateTime decidedAt,
        LocalDateTime createdAt) {
}
