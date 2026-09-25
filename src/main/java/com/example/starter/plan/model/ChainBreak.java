package com.example.starter.plan.model;

import java.time.LocalDate;

/**
 * 不可变车底交路断链记录：取消交路中间段时追加写入，记录被取消段及其时间序前后相邻段。
 *
 * @param id                  主键
 * @param stockNo             断链所属车底标识
 * @param opDate              断链发生的运营日期（Asia/Shanghai 日历日）
 * @param cancelledPlanId     被取消的中间段计划 id
 * @param predecessorPlanId   断链前段计划 id（被取消段的时间序前一已发布段）
 * @param successorPlanId     断链后段计划 id（被取消段的时间序后一已发布段）
 * @param reason              断链原因，如 CANCEL_MIDDLE
 * @param createdAt           记录创建时刻，UTC 毫秒
 */
public record ChainBreak(long id, String stockNo, LocalDate opDate, long cancelledPlanId,
                         long predecessorPlanId, long successorPlanId, String reason, long createdAt) {
}
