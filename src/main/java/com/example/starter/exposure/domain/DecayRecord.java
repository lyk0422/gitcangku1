package com.example.starter.exposure.domain;

import java.math.BigDecimal;

/**
 * 频次衰减记录 PO。按访客、公告和确认发生的 UTC 自然日累计 CONFIRMED 次数；
 * 与确认操作同事务写入，写入后不可篡改，跨零点重新从 1 计数且历史保留。
 *
 * @param id              自增主键
 * @param campaignId      公告编号
 * @param visitorId       合成访客编号
 * @param utcDate         确认发生的 UTC 自然日（java.time.LocalDate 对应的 java.sql.Date）
 * @param seqNo           当日第 N 次确认，N 从 1 起
 * @param decayWeight     衰减权重 = 1/N，保留 4 位小数 HALF_UP
 * @param reservationId   产生该记录的预占单编号
 * @param confirmedAtUtc  确认时刻（epoch 毫秒，UTC）
 */
public record DecayRecord(
        Long id,
        String campaignId,
        String visitorId,
        java.sql.Date utcDate,
        int seqNo,
        BigDecimal decayWeight,
        String reservationId,
        long confirmedAtUtc
) {
}
