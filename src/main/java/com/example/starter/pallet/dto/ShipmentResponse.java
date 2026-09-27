package com.example.starter.pallet.dto;

import java.time.Instant;
import java.util.List;

/**
 * 装运放行响应：固化托盘、批次、类别、分隔位与隔板版本快照。
 */
public record ShipmentResponse(
        String palletKey,
        String actor,
        int dividerCount,
        int dividerVersion,
        List<Item> items,
        Instant shippedAt
) {
    /**
     * 装运快照项：批次、类别、分隔位与快照序号。
     */
    public record Item(String batchKey, String category, int compartment, int seq) {
    }
}
