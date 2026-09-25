package com.example.starter.plan.model;

import java.time.LocalDate;

/**
 * 不可变抢占记录：固化抢占双方计划、各自等级与提交的 preemptKey。
 *
 * @param id                    主键
 * @param opDate                运营日期（Asia/Shanghai 日历日）
 * @param preemptingScheduleKey 抢占方计划业务键（本次发布的高等级草稿）
 * @param preemptedScheduleKey  被抢占方计划业务键（转为 PREEMPTED 的原已发布计划）
 * @param preemptingLevel       抢占方计划等级（其全部占用区段最高等级，固化）
 * @param preemptedLevel        被抢占方计划等级（固化）
 * @param preemptKey            本次抢占提交的 preemptKey，用于追溯
 * @param createdAt             记录创建时刻，UTC 毫秒
 */
public record PreemptionRecord(long id, LocalDate opDate, String preemptingScheduleKey,
                               String preemptedScheduleKey, int preemptingLevel, int preemptedLevel,
                               String preemptKey, long createdAt) {
}
