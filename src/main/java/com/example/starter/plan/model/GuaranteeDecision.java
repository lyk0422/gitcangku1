package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 批量保障改签决策历史（追加后不可变）。
 *
 * @param id                    主键
 * @param batchKey              批次键（批量请求的 requestKey）
 * @param connectionId          决策依据的接续 id
 * @param predecessorPlanId     决策时前车计划 id
 * @param successorPlanId       被改签的后车计划 id
 * @param predecessorVersion    决策所依据的前车计划版本
 * @param oldDepartureUtc       改签前后车始发时刻
 * @param newDepartureUtc       改签后后车始发时刻
 * @param shiftMinutes          后车整体延后分钟数
 * @param shortfallBeforeMinutes 改签前保障差额（分钟）
 * @param createdAtMillis       决策时刻，UTC 毫秒
 */
public record GuaranteeDecision(long id, String batchKey, long connectionId,
                                long predecessorPlanId, long successorPlanId,
                                int predecessorVersion, Instant oldDepartureUtc,
                                Instant newDepartureUtc, int shiftMinutes,
                                long shortfallBeforeMinutes, long createdAtMillis) {
}