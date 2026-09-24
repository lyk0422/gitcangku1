package com.example.starter.plan.model;

/**
 * 夜间跨零点计划对不可变记录，联合发布成功时写入。
 *
 * @param id             主键
 * @param nightPairKey   计划对业务键，全局唯一
 * @param sameDayPlanId  当日（跨零点起始运营日）计划 id
 * @param nextDayPlanId  次日计划 id
 * @param createdAt      创建时刻，UTC 毫秒
 */
public record NightPair(long id, String nightPairKey, long sameDayPlanId, long nextDayPlanId,
                        long createdAt) {
}
