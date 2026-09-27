package com.example.starter.batch.dto;

import java.time.Instant;
import java.util.List;

/**
 * 装运放行响应：固化托盘、批次、类别、分隔位与隔板版本快照。
 * openDurationMillis 为托盘从创建到装运的实际时长（毫秒，UTC 区间 [createdAt, shippedAt)）。
 */
public record ShipmentResponse(
        String palletKey,
        int dividerCount,
        int dividerVersion,
        int itemCount,
        int categoryCount,
        List<PalletLoadEntry> items,
        Instant shippedAt,
        long openDurationMillis
) {
}
