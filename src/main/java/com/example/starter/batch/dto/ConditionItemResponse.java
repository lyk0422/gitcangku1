package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 条件子项明细。closed=false 时 closedAt/closerId/closerRole/evidence 均为 null。
 *
 * @param itemKey conditionKey 内子项标识
 * @param description 创建时的条件说明
 * @param seq 创建请求中的顺序，从 1 开始
 * @param closed 是否已核销
 * @param closedAt 核销时刻（UTC），未核销为 null
 * @param closerId 核销批准人标识，未核销为 null
 * @param closerRole 核销批准角色，未核销为 null
 * @param evidence 核销证明说明，未核销为 null
 */
public record ConditionItemResponse(
        String itemKey,
        String description,
        int seq,
        boolean closed,
        Instant closedAt,
        String closerId,
        String closerRole,
        String evidence
) {
}
