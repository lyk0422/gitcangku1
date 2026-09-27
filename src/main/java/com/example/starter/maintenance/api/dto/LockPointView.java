package com.example.starter.maintenance.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 锁定点视图：含两个维护角色的确认证据与解除证据，均为创建后只追加的业务快照。
 *
 * @param seqNo          隔离令内规范化顺序（从 1 开始，稳定排序）
 * @param pointId        锁定点业务标识
 * @param confirmations  已发生确认（按角色名称升序）；每项含角色、操作人、UTC 确认时刻
 * @param releasedAt     该点解除时刻（UTC），未解除为 null
 * @param releasedBy     该点解除操作人，未解除为 null
 * @param releaseReason  该点解除原因，未解除为 null
 */
public record LockPointView(
        int seqNo,
        String pointId,
        List<ConfirmationView> confirmations,
        Instant releasedAt,
        String releasedBy,
        String releaseReason) {
}
