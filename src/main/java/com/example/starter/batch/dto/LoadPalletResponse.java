package com.example.starter.batch.dto;

import com.example.starter.batch.PalletStatus;

import java.time.Instant;
import java.util.List;

/**
 * 装载达成响应。
 * loadings 为本请求新达成的装载明细，按请求规范顺序排列；
 * dividerCount 为实际有效隔板数（实际值），requiredDividerCount 为最终隔离关系要求的隔板数（要求值），
 * dividerGap = 实际值 - 要求值（差额，0 表示恰好满足）。
 */
public record LoadPalletResponse(
        String palletKey,
        PalletStatus palletStatus,
        int dividerCount,
        int dividerVersion,
        int requiredDividerCount,
        int dividerGap,
        List<LoadingDetail> loadings,
        Instant loadedAt
) {
}
