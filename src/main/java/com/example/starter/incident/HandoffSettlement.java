package com.example.starter.incident;

import java.time.Instant;

/**
 * 不可变交接结算记录，对应 handoff_settlements 表。
 * 一条交接恰好一条结算（handoff_id 唯一）；reason 区分目标关闭/租约到期/任务终态，
 * returnedResourceKey 为归还资源键，detail 记录解绑任务等明细。记录只追加，不更新不删除。
 * 时间均为 UTC。
 */
public record HandoffSettlement(
        long id,
        long handoffId,
        String reason,
        String returnedResourceKey,
        String detail,
        Instant settledAt,
        Instant createdAt) {
}
