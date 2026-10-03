package com.example.starter.plan.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 计划接续诊断视图：当前风险标记、参与的全部接续（含实时保障差额）
 * 与当前未解除的风险时间窗。查询不改变任何状态。
 */
public record ConnectionDiagnosticsView(
        String scheduleKey,
        String planStatus,
        String connectionRisk,
        List<ConnectionView> connections,
        List<RiskWindow> openRiskWindows) {

    /**
     * 未解除的风险时间窗：后车可改签的始发时刻区间 [windowStartUtc, windowEndUtc)。
     */
    public record RiskWindow(long connectionId, String predecessorKey,
                             Instant windowStartUtc, Instant windowEndUtc,
                             int requiredMinutes, int actualMinutes, int deficitMinutes) {
    }
}