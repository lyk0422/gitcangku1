package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 接续保障不足的风险判定历史（追加后不可变）。
 * 固化判定所依据的前车版本、原新时刻与保障差额；取消接续或再次改签不改写。
 *
 * @param id                  主键
 * @param connectionId        触发判定的接续 id
 * @param predecessorPlanId   判定时前车计划 id
 * @param successorPlanId     判定时后车计划 id
 * @param predecessorVersion  判定所依据的前车计划版本
 * @param oldArrivalUtc       判定前前车终到时刻
 * @param newArrivalUtc       判定后前车终到时刻
 * @param oldDepartureUtc     判定前后车始发时刻
 * @param newDepartureUtc     判定后后车始发时刻
 * @param guaranteeMinutes    保障分钟数要求值
 * @param actualMinutes       实际接续时长（分钟，可为负）
 * @param shortfallMinutes    保障差额（分钟）= 要求值 - 实际值，恒为正
 * @param windowStartUtc      可选改签时间窗起点（含）
 * @param windowEndUtc        可选改签时间窗终点（不含）
 * @param marked              是否已将后车标记为 CONNECTION_AT_RISK
 * @param createdAtMillis     判定时刻，UTC 毫秒
 */
public record ConnectionRisk(long id, long connectionId, long predecessorPlanId,
                             long successorPlanId, int predecessorVersion,
                             Instant oldArrivalUtc, Instant newArrivalUtc,
                             Instant oldDepartureUtc, Instant newDepartureUtc,
                             int guaranteeMinutes, long actualMinutes, long shortfallMinutes,
                             Instant windowStartUtc, Instant windowEndUtc,
                             boolean marked, long createdAtMillis) {
}