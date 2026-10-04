package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 计划接续诊断视图：当前风险标记与全部生效接续的实时评估（只读，不改变状态）。
 */
public record ConnectionDiagnosisView(
        String scheduleKey,
        String riskStatus,
        List<ConnectionEvaluationView> evaluations) {
}