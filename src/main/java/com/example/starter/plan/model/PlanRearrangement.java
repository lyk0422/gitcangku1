package com.example.starter.plan.model;

/**
 * 气象限速触发的计划整体顺延重排记录（不可变业务证据）。
 *
 * @param id                      主键
 * @param planId                  被顺延计划 id
 * @param scheduleKey             被顺延计划业务键
 * @param opType                  触发重排的操作：PUBLISH / RESCHEDULE
 * @param shiftMinutes            实际整体顺延分钟数，为线路最小间隔的整数倍，恒大于 0
 * @param restrictionSnapshotJson 重排时使用的限速令版本快照（JSON）
 * @param originalJson            原计划时刻快照（JSON）
 * @param shiftedJson             顺延后新计划时刻快照（JSON）
 * @param createdAtMillis         记录创建时刻，UTC 毫秒
 */
public record PlanRearrangement(long id, long planId, String scheduleKey, String opType,
                                int shiftMinutes, String restrictionSnapshotJson,
                                String originalJson, String shiftedJson, long createdAtMillis) {
}
