package com.example.starter.plan.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 计划接续风险诊断视图：当前风险标记、可选改签时间窗（仅风险存续期非空）
 * 及全部生效接续的实时保障评估。只读，不改变任何状态。
 */
public record ConnectionRiskView(
        String scheduleKey,
        String riskStatus,
        Instant windowStartUtc,
        Instant windowEndUtc,
        List<ConnectionDeficitView> inboundConnections) {
}
