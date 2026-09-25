package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 批次有效期查询响应。remainingMinutes 为查询时刻距有效期截止的整分钟数，到期后为负数；
 * expired 为查询时刻是否已到期；cumulativeExtendedMinutes 为已生效延期累计顺延分钟；
 * effectiveExtensions 为已生效延期次数（上限 3 次，累计顺延不超过原保质分钟两倍）。
 */
public record ShelfLifeResponse(
        String batchKey,
        int shelfLifeMinutes,
        Instant producedAt,
        Instant expiresAt,
        long remainingMinutes,
        boolean expired,
        int cumulativeExtendedMinutes,
        int effectiveExtensions
) {
}
