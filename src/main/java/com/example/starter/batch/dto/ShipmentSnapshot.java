package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 装运快照（只读）：固化装运时的隔板数量、隔板版本、批次数与类别数，后续修改不追溯。
 */
public record ShipmentSnapshot(
        int dividerCount,
        int dividerVersion,
        int itemCount,
        int categoryCount,
        Instant shippedAt
) {
}
