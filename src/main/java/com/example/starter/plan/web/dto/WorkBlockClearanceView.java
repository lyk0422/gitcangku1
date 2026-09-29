package com.example.starter.plan.web.dto;

/**
 * 封锁受影响计划及其处理结果视图（历史不可变）。
 *
 * @param scheduleKey    被处理的原计划业务键
 * @param action         登记事务内实际动作：CANCELLED 撤销 / RESCHEDULED 整单改签
 * @param newScheduleKey 改签后继新计划业务键；撤销时为 null
 * @param oldPlan        原计划当前快照（CANCELLED，保留原区段与时段，不随后续改签变化）
 * @param newPlan        后继新计划当前快照；撤销时为 null
 */
public record WorkBlockClearanceView(String scheduleKey, String action, String newScheduleKey,
                                     PlanResponse oldPlan, PlanResponse newPlan) {
}
