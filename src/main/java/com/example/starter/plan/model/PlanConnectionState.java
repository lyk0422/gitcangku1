package com.example.starter.plan.model;

/**
 * 计划当前接续风险标记（最新状态，可被清除；历史判定见 ConnectionRisk）。
 *
 * @param planId           计划 id
 * @param riskStatus       风险状态
 * @param riskConnectionId 触发当前风险标记的接续 id，无风险时为 null
 */
public record PlanConnectionState(long planId, RiskStatus riskStatus, Long riskConnectionId) {
}