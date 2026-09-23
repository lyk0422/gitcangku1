package com.example.starter.plan.model;

/**
 * 封锁切换一对一替代链（激活时写入，不可变）。
 *
 * @param id                主键
 * @param switchId          所属切换单 id
 * @param suspendedPlanId   被挂起旧计划 id，全表唯一
 * @param replacementPlanId 替代发布计划 id，全表唯一
 */
public record DisruptionReplaceLink(long id, long switchId, long suspendedPlanId,
                                    long replacementPlanId) {
}
