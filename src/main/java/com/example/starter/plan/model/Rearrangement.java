package com.example.starter.plan.model;

/**
 * 气象限速触发的计划整体顺延重排记录（头部），追加后不可变。
 *
 * @param id           主键
 * @param planId       被重排计划 id
 * @param scheduleKey  被重排计划业务键（冗余固化）
 * @param opType       触发场景：PUBLISH 发布 / RESCHEDULE 改签
 * @param shiftMinutes 整体顺延分钟数，为线路既有最小间隔的整数倍
 * @param operator     触发本次发布/改签的操作者标识，空操作者以空串固化
 * @param createdAt    重排记录创建时刻，UTC 毫秒
 */
public record Rearrangement(long id, long planId, String scheduleKey, String opType,
                            long shiftMinutes, String operator, long createdAt) {
}
