package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 批量保障改签决策证据（不可变历史）。成功批次在事务提交时追加；
 * 失败批次整体回滚不留痕。所有时刻 UTC。
 *
 * @param id                    主键
 * @param batchRequestKey       批量保障改签幂等键（同批一致）
 * @param successorPlanId       改签后后车（新计划）id；未改签为 null
 * @param oldSuccessorPlanId    改签前后车旧计划 id；未改签为 null
 * @param newSuccessorPlanId    改签后后车新计划 id；未改签为 null
 * @param connectionId          依据的接续登记 id；与具体接续无关为 null
 * @param predecessorPlanId     决策时前车（当前生效计划）id；未改签为 null
 * @param successorBaseVersion  改签所依据的后车旧版本；未改签为 null
 * @param oldDepartureUtc       后车原始发时刻；未改签为 null
 * @param newDepartureUtc       后车新始发时刻；未改签为 null
 * @param predecessorArrivalUtc 决策时前车终到时刻；未改签为 null
 * @param guaranteeMinutes      接续保障分钟数；未改签为 null
 * @param requiredGapMinutes    要求间隔分钟数；未改签为 null
 * @param actualGapMinutes      改签后实际间隔分钟数；未改签为 null
 * @param shortfallMinutes      决策时保障差额分钟数；未改签为 null
 * @param itemResult            条目结果：REBOOKED / REJECTED
 * @param reasonCode            失败原因码；成功为 null
 * @param createdAt             记录生成时刻，UTC 毫秒
 */
public record ConnectionDecision(long id, String batchRequestKey, Long successorPlanId,
                                 Long oldSuccessorPlanId, Long newSuccessorPlanId, Long connectionId,
                                 Long predecessorPlanId, Integer successorBaseVersion,
                                 Instant oldDepartureUtc, Instant newDepartureUtc,
                                 Instant predecessorArrivalUtc, Integer guaranteeMinutes,
                                 Integer requiredGapMinutes, Long actualGapMinutes,
                                 Long shortfallMinutes, String itemResult, String reasonCode,
                                 long createdAt) {
}
