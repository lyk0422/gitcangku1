package com.example.starter.plan.web.dto;

/**
 * 原子改签响应：旧计划（已取消，历史占用保留）与新计划（已发布）的改签后快照。
 */
public record RescheduleResponse(PlanResponse oldPlan, PlanResponse newPlan) {
}
