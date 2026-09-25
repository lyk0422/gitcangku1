package com.example.starter.plan.model;

import java.time.LocalDate;

/**
 * 车底交路断链记录，追加后不可变。
 *
 * @param id                   主键
 * @param stockKey             断链车底标识
 * @param opDate               断链发生运营日（Asia/Shanghai 日历日）
 * @param cancelledPlanId      被移除计划 id
 * @param cancelledScheduleKey 被移除计划业务键
 * @param prevScheduleKey      断点前一已发布段业务键，null 表示被移除段为链首
 * @param nextScheduleKey      断点后一已发布段业务键（最近后续段）
 * @param createdAt            记录创建时刻，UTC 毫秒
 */
public record ChainBreak(long id, String stockKey, LocalDate opDate, long cancelledPlanId,
                         String cancelledScheduleKey, String prevScheduleKey,
                         String nextScheduleKey, long createdAt) {
}
