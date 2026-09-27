package com.example.starter.batch.dto;

import java.time.Instant;
import java.util.List;

/**
 * 装载响应：按请求规范顺序返回本次装载明细；dividerVersion 为装载时隔板版本。
 */
public record LoadResponse(
        String palletKey,
        int dividerVersion,
        List<PalletLoadEntry> items,
        Instant loadedAt
) {
}
