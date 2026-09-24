package com.example.starter.plan.web.dto;

/**
 * 夜间计划对响应：firstPlan 为运营日 D 的夜间跨零点计划，secondPlan 为运营日 D+1 的次日计划。
 */
public record PlanPairResponse(String nightPairKey, PlanResponse firstPlan,
                               PlanResponse secondPlan) {
}
