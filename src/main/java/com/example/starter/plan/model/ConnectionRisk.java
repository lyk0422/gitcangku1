package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 接续风险记录（追加式历史，不可改写）：前车改签延后导致后车保障不足时生成，
 * 固化风险判定所依据的前车版本、原新时刻与保障差额；后续取消接续或再次改签不改写本记录。
 *
 * @param id                       主键
 * @param connectionId             触发风险的接续 id
 * @param predecessorPlanId        风险判定时生效的前车（改签后新计划）id
 * @param predecessorVersion       风险判定所依据的前车版本
 * @param successorPlanId          保障不足的后车计划 id
 * @param predecessorOldArrivalUtc 前车原终到时刻（改签前），UTC
 * @param predecessorNewArrivalUtc 前车新终到时刻（改签后），UTC
 * @param requiredMinutes          要求的保障分钟数
 * @param actualMinutes            实际接续分钟数（后车始发减前车新终到），可为负
 * @param deficitMinutes           保障差额（要求减实际），分钟
 * @param windowStartUtc           可选改签时间窗起点（含）：前车新终到加保障分钟数，UTC
 * @param windowEndUtc             可选改签时间窗终点（不含）：后车运营日结束，UTC
 * @param createdAt                记录生成时刻，UTC 毫秒
 */
public record ConnectionRisk(long id, long connectionId, long predecessorPlanId,
                             int predecessorVersion, long successorPlanId,
                             Instant predecessorOldArrivalUtc, Instant predecessorNewArrivalUtc,
                             int requiredMinutes, long actualMinutes, long deficitMinutes,
                             Instant windowStartUtc, Instant windowEndUtc, long createdAt) {
}
