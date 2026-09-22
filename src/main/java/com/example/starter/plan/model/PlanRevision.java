package com.example.starter.plan.model;

/**
 * 改签前后继不可变关联：一次改签把前驱（旧已发布计划）取消、后继（新计划）发布。
 *
 * @param predecessorPlanId 前驱计划 id（改签时被取消），一个计划最多一个直接后继
 * @param successorPlanId   后继计划 id（改签时发布），一个计划最多一个直接前驱
 * @param createdAt         改签提交时刻，UTC 毫秒
 */
public record PlanRevision(long predecessorPlanId, long successorPlanId, long createdAt) {
}
