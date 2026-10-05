package com.example.starter.plan.model;

/**
 * 限速重排记录主表：成功重排时固化，追加后不可变。
 *
 * @param id           主键
 * @param planId       被重排计划 id
 * @param scheduleKey  被重排计划业务键
 * @param opType       触发操作：PUBLISH 发布 / RESCHEDULE 改签
 * @param shiftMinutes 整体顺延分钟数，大于 0
 * @param createdAt    重排时刻，UTC 毫秒
 */
public record Rearrangement(long id, long planId, String scheduleKey, String opType,
                            long shiftMinutes, long createdAt) {
}
