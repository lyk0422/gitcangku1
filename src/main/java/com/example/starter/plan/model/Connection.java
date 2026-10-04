package com.example.starter.plan.model;

/**
 * 接续关系当前状态。前/后车改签后关联跟随到新计划；
 * 取消只更新状态与取消时刻，历史判定见 rail_connection_event。
 *
 * @param id                主键
 * @param predecessorPlanId 前车（先到）计划 id，关联 rail_day_plan.id
 * @param successorPlanId   后车（接续）计划 id，关联 rail_day_plan.id
 * @param guaranteeMinutes  保障分钟数：后车始发不早于前车终到加该分钟数，合法范围 5~240
 * @param crossesMidnight   是否明确跨零点：true 允许后车在下一运营日
 * @param status            接续状态
 * @param createdAt         登记时刻，UTC 毫秒
 * @param cancelledAt       取消时刻，UTC 毫秒；未取消为 null
 */
public record Connection(long id, long predecessorPlanId, long successorPlanId,
                         int guaranteeMinutes, boolean crossesMidnight,
                         ConnectionStatus status, long createdAt, Long cancelledAt) {
}