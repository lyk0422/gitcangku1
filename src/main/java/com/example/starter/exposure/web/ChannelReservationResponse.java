package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.ChannelReservation;
import com.example.starter.exposure.domain.ReservationStatus;

import java.time.LocalDate;

/**
 * 渠道预占明细视图。渠道、公告、访客、日期与预占时刻均为创建时固化值。
 *
 * @param reservationId 预占单编号
 * @param channelKey    创建时固化的渠道编号
 * @param campaignId    创建时固化的公告编号
 * @param visitorId     合成访客编号
 * @param utcDate       额度所属 UTC 日，格式 yyyy-MM-dd
 * @param status        预占状态
 * @param createdAtUtc  预占时刻，epoch 毫秒，UTC
 * @param terminalAtUtc 终态时刻，未进入终态为 null
 */
public record ChannelReservationResponse(
        String reservationId,
        String channelKey,
        String campaignId,
        String visitorId,
        LocalDate utcDate,
        ReservationStatus status,
        long createdAtUtc,
        Long terminalAtUtc
) {
    public static ChannelReservationResponse from(ChannelReservation r) {
        return new ChannelReservationResponse(
                r.reservationId(),
                r.channelKey(),
                r.campaignId(),
                r.visitorId(),
                r.utcDate().toLocalDate(),
                r.status(),
                r.createdAtUtc(),
                r.terminalAtUtc());
    }
}
