package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 隔板配置响应。
 * dividerCount 为配置后的实际隔板数（实际值），previousDividerCount 为变更前要求对照的旧值，
 * dividerVersion 为当前隔板版本，changed 标识本次是否实际产生新版本，delta = 新值 - 旧值（差额）。
 */
public record ConfigureDividersResponse(
        String palletKey,
        int dividerCount,
        int previousDividerCount,
        int dividerVersion,
        int delta,
        boolean changed,
        Instant configuredAt
) {
}
