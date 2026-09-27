package com.example.starter.maintenance.domain;

import java.time.Instant;
import java.util.List;

/**
 * 隔离锁定点快照及其确认/解除状态。
 *
 * @param seqNo          隔离令内规范化顺序（从 1 开始）
 * @param pointId        锁定点业务标识（创建后固化）
 * @param confirmations  已发生的角色确认（取自 lock_event，按角色升序）
 * @param releasedAt     该点解除时刻（UTC），未解除为 null
 * @param releasedBy     该点解除操作人，未解除为 null
 * @param releaseReason  该点解除原因，未解除为 null
 */
public record LockPoint(
        int seqNo,
        String pointId,
        List<LockConfirmation> confirmations,
        Instant releasedAt,
        String releasedBy,
        String releaseReason) {

    public boolean confirmedBy(String role) {
        return confirmations.stream().anyMatch(c -> c.role().equals(role));
    }
}
