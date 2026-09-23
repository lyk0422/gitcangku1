package com.example.starter.spectrum.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 方案提交结果 / 不可变历史记录视图。
 */
public record PlanResultView(
        String planKey,
        String requestId,
        int versionFrom,
        int versionTo,
        NetworkStateView before,
        NetworkStateView after,
        List<StationInterference> interferenceSummary,
        OffsetDateTime createdAt
) {
    /**
     * 单个接收台站的累计干扰裁决明细：静默台站 totalInterference 恒为0且不参与预算校验。
     */
    public record StationInterference(
            String stationId,
            int channel,
            int totalInterference,
            int interferenceBudget,
            boolean withinBudget
    ) {
    }
}
