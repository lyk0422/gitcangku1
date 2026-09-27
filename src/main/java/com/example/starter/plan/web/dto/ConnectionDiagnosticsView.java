package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 计划接续诊断视图：计划快照、关联接续（含实时评估）、风险与决策历史。
 * 仅查询组装，不改变任何状态。
 */
public record ConnectionDiagnosticsView(
        PlanResponse plan,
        List<ConnectionView> connections,
        List<ConnectionRiskView> risks,
        List<ConnectionDecisionView> decisions) {
}
