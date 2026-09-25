package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 抢占记录（不可变），与被抢占计划降级、抢占草稿发布在同一事务写入。
 *
 * @param id                     主键
 * @param preemptingPlanId       抢占方计划 id
 * @param preemptingScheduleKey  抢占方计划业务键（快照）
 * @param preemptedPlanId        被抢占计划 id
 * @param preemptedScheduleKey   被抢占计划业务键（快照）
 * @param preemptingLevel        抢占方计划等级（其全部占用区段最高等级，抢占时快照）
 * @param preemptedLevel         被抢占方计划等级（抢占时快照）
 * @param createdAt              抢占发生时刻，UTC
 */
public record Preemption(long id, long preemptingPlanId, String preemptingScheduleKey,
                         long preemptedPlanId, String preemptedScheduleKey,
                         int preemptingLevel, int preemptedLevel, Instant createdAt) {
}
