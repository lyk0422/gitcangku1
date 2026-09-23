package com.example.starter.plan.model;

/**
 * 切换单提交的旧计划到替代草稿计划的一对一映射（不可变）。
 *
 * @param id                 主键
 * @param switchId           所属切换单 id
 * @param oldPlanId          旧计划 id
 * @param replacementPlanId  替代草稿计划 id
 * @param expectedOldVersion 提交时记录的旧计划期望版本
 */
public record DisruptionMapping(long id, long switchId, long oldPlanId, long replacementPlanId,
                                int expectedOldVersion) {
}
