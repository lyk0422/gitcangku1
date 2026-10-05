package com.example.starter.plan.model;

/**
 * 限速重排记录（不可变）。发布或改签触发整体顺延时追加，
 * 固化当时使用的限速令版本、原计划时刻与新计划时刻；
 * 后续撤销或修订限速令不改写已生成记录。
 *
 * @param id                   主键
 * @param planId               被重排计划 id
 * @param scheduleKey          被重排计划业务键
 * @param opType               触发重排的操作：PUBLISH 发布 / RESCHEDULE 改签
 * @param shiftMinutes         整体顺延时长，单位分钟，为线路最小间隔的整数倍
 * @param restrictionRefsJson  重排依据的限速令版本快照（JSON）
 * @param originalJson         原计划时刻（JSON，UTC 毫秒，计划内序号升序）
 * @param shiftedJson          新计划时刻（JSON，UTC 毫秒，与原计划同序）
 * @param createdAt            记录生成时刻，UTC 毫秒
 */
public record Rearrangement(long id, long planId, String scheduleKey, String opType,
                            long shiftMinutes, String restrictionRefsJson,
                            String originalJson, String shiftedJson, long createdAt) {
}
