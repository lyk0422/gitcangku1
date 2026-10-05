package com.example.starter.plan.model;

/**
 * 重排引用的限速令快照：固化使用的限速令版本与原计划受影响分钟数，追加后不可变。
 *
 * @param id                 主键
 * @param rearrangementId    所属重排记录 id
 * @param restrictionKey     被违反的限速令业务键
 * @param restrictionVersion 重排时使用的限速令版本
 * @param sectionId          限速区段 ID
 * @param maxSpeedKmh        重排时限速令的最高速度 km/h
 * @param affectedMinutes    原计划在该限速令受影响时段内的运行分钟数（逐段累计）
 */
public record RearrangementRestriction(long id, long rearrangementId, String restrictionKey,
                                       int restrictionVersion, String sectionId, int maxSpeedKmh,
                                       long affectedMinutes) {
}
