package com.example.starter.plan.model;

/**
 * 封锁切换替代关联（不可变一对一）。激活提交时为每个被挂起的旧计划写入唯一旧→替代映射，
 * 一个旧计划最多被替代一次，一个替代计划最多替代一个旧计划，追加后不修改、不删除。
 * 与改签链（rail_plan_reschedule_link）相互独立，沿任一链环遍历时两者均不可成环。
 *
 * @param id                 主键
 * @param switchId           所属切换单 id，关联 rail_section_switch.id
 * @param oldPlanId          被挂起的旧计划 id，关联 rail_day_plan.id，全表唯一
 * @param replacementPlanId  替代旧计划的新计划 id，关联 rail_day_plan.id，全表唯一
 * @param createdAt          关联创建时刻，UTC 毫秒
 */
public record ReplacementLink(long id, long switchId, long oldPlanId, long replacementPlanId,
                              long createdAt) {
}
