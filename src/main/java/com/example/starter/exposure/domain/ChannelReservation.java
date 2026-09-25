package com.example.starter.exposure.domain;

/**
 * 渠道预占记录 PO。创建时固化公告、访客、UTC 日、渠道与预占时刻；
 * 公告之后迁移到其他渠道不影响本记录，结算始终按固化的 channelKey 进行。
 *
 * @param reservationId 预占单编号，与 exposure_reservation 一一对应
 * @param channelKey    创建时固化的渠道编号
 * @param campaignId    创建时固化的公告编号
 * @param visitorId     合成访客编号
 * @param utcDate       额度所属 UTC 日（java.time.LocalDate 对应的 java.sql.Date）
 * @param status        预占状态，与主预占单同步迁移
 * @param createdAtUtc  预占时刻（epoch 毫秒，UTC）
 * @param terminalAtUtc 进入终态的时刻，未到终态为 null
 */
public record ChannelReservation(
        String reservationId,
        String channelKey,
        String campaignId,
        String visitorId,
        java.sql.Date utcDate,
        ReservationStatus status,
        long createdAtUtc,
        Long terminalAtUtc
) {
}
