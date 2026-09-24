package com.example.starter.plan.model;

import java.time.LocalDate;

/**
 * 夜间跨零点计划对不可变记录，联合发布成功后写入。
 *
 * @param id           主键
 * @param nightPairKey 计划对业务键，全局唯一
 * @param firstPlanId  首计划（运营日 D 的夜间跨零点计划）id
 * @param secondPlanId 次日计划（运营日 D+1）id
 * @param firstOpDate  首计划运营日期（Asia/Shanghai 日历日）
 * @param secondOpDate 次日计划运营日期，等于 firstOpDate + 1 天
 * @param createdAt    计划对记录创建时刻，UTC 毫秒
 */
public record NightPair(long id, String nightPairKey, long firstPlanId, long secondPlanId,
                        LocalDate firstOpDate, LocalDate secondOpDate, long createdAt) {
}
