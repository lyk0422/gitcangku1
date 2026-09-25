package com.example.starter.exposure.domain;

import java.math.BigDecimal;

/**
 * 频次衰减权重明细 PO。按访客、公告、UTC 自然日累计 CONFIRMED 次数，
 * 每次确认写一行；sequence_no 为当日第 N 次确认（N 从 1 起），
 * decayWeight = 1 / N，保留 4 位小数 HALF_UP；历史保留不改写。
 *
 * @param id              自增主键
 * @param campaignId      所属公告编号
 * @param visitorId       合成访客编号
 * @param utcDate         次数累计所属 UTC 自然日（java.sql.Date）
 * @param sequenceNo      当日第 N 次确认，N 从 1 起；跨 UTC 零点重新从 1 计数
 * @param decayWeight     衰减权重 1/N，DECIMAL(10,4)，HALF_UP；不影响额度扣减与冷却判定
 * @param reservationId   产生该记录的预占单编号
 * @param confirmedAtUtc  确认时刻，epoch 毫秒，UTC
 */
public record DecayRecord(
        Long id,
        String campaignId,
        String visitorId,
        java.sql.Date utcDate,
        int sequenceNo,
        BigDecimal decayWeight,
        String reservationId,
        long confirmedAtUtc
) {
}
