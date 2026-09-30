package com.example.starter.exposure.domain;

/**
 * 抑制名单取消预占的明细 PO。一笔预占至多因抑制被取消一次（reservationId 唯一）；
 * 同一访客多次加入名单产生不同批次（{@link #suppressedAtUtc} 区分）。
 *
 * @param reservationId   被取消的预占单编号
 * @param campaignId      预占所属公告编号
 * @param visitorId       被抑制访客编号
 * @param utcDate         预占额度所属 UTC 日（java.time.LocalDate 对应的 java.sql.Date）
 * @param suppressedAtUtc 所属抑制批次的生效时刻，epoch 毫秒，UTC
 */
public record SuppressionCancellation(
        String reservationId,
        String campaignId,
        String visitorId,
        java.sql.Date utcDate,
        long suppressedAtUtc
) {
}
