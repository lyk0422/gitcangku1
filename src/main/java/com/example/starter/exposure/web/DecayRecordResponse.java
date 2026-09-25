package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.DecayRecord;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 频次衰减权重明细视图。
 *
 * @param campaignId      公告编号
 * @param visitorId       合成访客编号
 * @param utcDate         次数累计所属 UTC 自然日，格式 yyyy-MM-dd
 * @param sequenceNo      当日第 N 次确认，N 从 1 起；跨 UTC 零点重新从 1 计数
 * @param decayWeight     衰减权重 1/N，保留 4 位小数 HALF_UP
 * @param reservationId   产生该记录的预占单编号
 * @param confirmedAtUtc  确认时刻，epoch 毫秒，UTC
 */
public record DecayRecordResponse(
        String campaignId,
        String visitorId,
        LocalDate utcDate,
        int sequenceNo,
        BigDecimal decayWeight,
        String reservationId,
        long confirmedAtUtc
) {
    public static DecayRecordResponse from(DecayRecord record) {
        return new DecayRecordResponse(
                record.campaignId(),
                record.visitorId(),
                record.utcDate().toLocalDate(),
                record.sequenceNo(),
                record.decayWeight(),
                record.reservationId(),
                record.confirmedAtUtc());
    }
}
