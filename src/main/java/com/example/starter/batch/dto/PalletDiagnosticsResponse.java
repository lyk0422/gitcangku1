package com.example.starter.batch.dto;

import com.example.starter.batch.PalletStatus;

import java.util.List;
import java.util.Map;

/**
 * 托盘诊断（只读）：隔板实际值/要求值/差额、类别到分隔位映射、各状态装载数与待复核数。
 * requiredDividers = max(最大分隔位编号, 在托类别数) - 1；dividerDeficit = max(0, 要求 - 实际)。
 */
public record PalletDiagnosticsResponse(
        String palletKey,
        PalletStatus status,
        int actualDividers,
        int requiredDividers,
        int dividerDeficit,
        boolean dividerSufficient,
        int dividerVersion,
        Map<String, List<Integer>> categoryCompartments,
        int currentLoadCount,
        int shippedLoadCount,
        int unloadedLoadCount,
        int pendingReviewCount
) {
}
