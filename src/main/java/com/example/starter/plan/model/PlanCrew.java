package com.example.starter.plan.model;

/**
 * 计划发布时刻的乘务组快照头：记录该计划值乘线路与当时要求的线路等级。
 * 发布后不可变；计划取消后保留，作为历史发布的资格依据。
 *
 * @param planId        所属计划 id，关联 rail_day_plan.id，唯一
 * @param lineId        值乘线路 ID
 * @param requiredGrade 发布时行程要求的最低线路等级
 * @param createdAt     快照创建时刻，UTC 毫秒
 */
public record PlanCrew(long planId, String lineId, int requiredGrade, long createdAt) {
}
