package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 锁定命中已生效弃用窗口的 422 明细：返回解析时刻、实际命中数量与稳定排序的阻断列表。
 */
public record DeprecationLockBlockedDetails(
        Instant resolvedAt,
        int blockedCount,
        List<DeprecationBlockedView> blocked) {
}
