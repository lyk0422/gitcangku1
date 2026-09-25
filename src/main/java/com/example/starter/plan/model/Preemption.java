package com.example.starter.plan.model;

import java.time.LocalDate;

/**
 * 不可变抢占记录：一次抢占中一对抢占方/被抢占计划的固化快照。
 *
 * @param id                主键
 * @param preemptKey        抢占幂等键，全局唯一
 * @param opDate            运营日期（Asia/Shanghai 日历日）
 * @param winnerPlanId      抢占方计划 id
 * @param winnerScheduleKey 抢占方计划业务键
 * @param winnerLevel       抢占方计划等级（发布时继承）
 * @param loserPlanId       被抢占计划 id，一张计划最多被抢占一次
 * @param loserScheduleKey  被抢占计划业务键
 * @param loserLevel        被抢占计划等级（被抢占时其全部占用区段最高等级）
 * @param createdAt         抢占提交时刻，UTC 毫秒
 */
public record Preemption(long id, String preemptKey, LocalDate opDate,
                         long winnerPlanId, String winnerScheduleKey, int winnerLevel,
                         long loserPlanId, String loserScheduleKey, int loserLevel,
                         long createdAt) {
}
