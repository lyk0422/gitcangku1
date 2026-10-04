package com.example.starter.plan.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 风险后车诊断视图：当前标记 CONNECTION_AT_RISK 的计划及其各生效接续的保障差额。
 */
public record RiskPlanView(String scheduleKey, Instant firstStartUtc,
                           List<RiskConnectionView> connections) {
}