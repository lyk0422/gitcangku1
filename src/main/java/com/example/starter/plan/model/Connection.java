package com.example.starter.plan.model;

/**
 * 两趟已发布计划间的接续保障关系。同一前后车计划对全局一行；
 * 任一端计划改签后关系指向新计划并重新核算；取消仅置状态不删行。
 *
 * @param id                主键
 * @param predecessorPlanId 前车（先终到）计划 id
 * @param successorPlanId   后车（后始发）计划 id
 * @param guaranteeMinutes  要求保障分钟数（0~1440）
 * @param fromStation       前车终到站
 * @param toStation         后车始发站，必须与终到站一致
 * @param crossesMidnight   是否明确跨零点（后车运营日为前车次日）
 * @param status            接续状态
 * @param createdAt         登记时刻，UTC 毫秒
 * @param updatedAt         最近变更时刻，UTC 毫秒
 */
public record Connection(long id, long predecessorPlanId, long successorPlanId,
                         int guaranteeMinutes, String fromStation, String toStation,
                         boolean crossesMidnight, ConnectionStatus status,
                         long createdAt, long updatedAt) {
}