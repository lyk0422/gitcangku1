package com.example.starter.batch.dto;

import com.example.starter.batch.PalletStatus;

import java.time.Instant;
import java.util.List;

/**
 * 托盘概要响应。loadings 仅含当前 ACTIVE 装载，按装载请求规范顺序（seq）排列。
 */
public record PalletResponse(
        String palletKey,
        PalletStatus status,
        int dividerCount,
        int dividerVersion,
        List<LoadingDetail> loadings,
        Instant createdAt
) {
}
