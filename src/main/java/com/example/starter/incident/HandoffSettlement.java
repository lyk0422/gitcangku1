package com.example.starter.incident;

import java.time.Instant;

/**
 * 交接结算实体，对应 handoff_settlements 表（不可变，仅插入）。
 * 每个资源项至多一条结算（uk_settlement_item 唯一约束兜底）；
 * returnedToIncidentId 为归还去向（来源事件）。时间均为 UTC。
 */
public record HandoffSettlement(
        long id,
        long handoffId,
        long itemId,
        String resourceKey,
        SettlementReason reason,
        long returnedToIncidentId,
        Instant settledAt) {
}
