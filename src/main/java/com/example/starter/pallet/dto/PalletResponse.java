package com.example.starter.pallet.dto;

import java.time.Instant;

/**
 * 托盘概要响应。时间为 UTC；shippedAt 等未发生事件的时间为 null。
 */
public record PalletResponse(
        String palletKey,
        String status,
        int dividerCount,
        int dividerVersion,
        Instant createdAt
) {
}
