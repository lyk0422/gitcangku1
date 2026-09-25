package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 条件子项明细。cleared 为 null 表示尚未核销；核销后携带核销人、角色、证明说明与核销时间。
 */
public record ConditionItemResponse(
        String itemKey,
        String description,
        boolean cleared,
        String clearedBy,
        String clearedRole,
        String evidence,
        Instant clearedAt
) {
}
