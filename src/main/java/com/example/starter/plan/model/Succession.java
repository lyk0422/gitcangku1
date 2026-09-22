package com.example.starter.plan.model;

/**
 * 日计划改签前后继不可变关联。改签事务提交时追加一行，之后不再修改或删除。
 *
 * @param id                  主键
 * @param predecessorPlanId   改签转出的旧计划 id（改签后 CANCELLED，占用原样保留）
 * @param successorPlanId     改签转入的新计划 id（改签后 PUBLISHED）
 * @param createdAtMillis     关联创建时刻（改签提交时刻），UTC 毫秒
 */
public record Succession(long id, long predecessorPlanId, long successorPlanId,
                         long createdAtMillis) {
}
