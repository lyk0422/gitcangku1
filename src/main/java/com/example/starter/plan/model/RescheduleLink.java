package com.example.starter.plan.model;

/**
 * 改签前后继关联（不可变）。一个计划最多一个直接前驱与一个直接后继，
 * 关联在改签事务提交时追加，之后不修改、不删除。
 *
 * @param id                主键
 * @param predecessorPlanId 直接前驱（被改签取消的旧计划）id，关联 rail_day_plan.id，全表唯一
 * @param successorPlanId   直接后继（改签发布的新计划）id，关联 rail_day_plan.id，全表唯一
 * @param createdAt         关联创建时刻，UTC 毫秒
 */
public record RescheduleLink(long id, long predecessorPlanId, long successorPlanId, long createdAt) {
}
