package com.example.starter.pallet.dto;

import java.time.Instant;

/**
 * 隔板配置变更响应：变更后的隔板数量与版本。
 */
public record DividerResponse(
        String palletKey,
        int dividerCount,
        int dividerVersion,
        Instant changedAt
) {
}
