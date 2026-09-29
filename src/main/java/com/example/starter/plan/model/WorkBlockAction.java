package com.example.starter.plan.model;

/**
 * 封锁处理结果记录：登记封锁的同一事务内，对每条受影响已发布计划执行的动作（不可变）。
 *
 * @param id              主键
 * @param blockId         所属封锁 id，关联 rail_work_block.id
 * @param planId          被处理的原计划 id，关联 rail_day_plan.id；该计划保留为 CANCELLED 且历史占用不改写
 * @param action          实际动作：CANCELLED 撤销 / RESCHEDULED 整单改签
 * @param successorPlanId 改签后继新计划 id；撤销时为 null
 * @param createdAt       记录创建时刻，UTC 毫秒
 */
public record WorkBlockAction(long id, long blockId, long planId, BlockClearanceAction action,
                              Long successorPlanId, long createdAt) {
}
