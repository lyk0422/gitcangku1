package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.Reservation;

import java.time.LocalDate;

/**
 * 访客历史已确认曝光审计视图。抑制生效后这些记录仍可审计，
 * 但不参与任何新预占发放。
 *
 * @param reservationId 预占单编号
 * @param campaignId    所属公告编号
 * @param visitorId     合成访客编号
 * @param utcDate       额度所属 UTC 日，格式 yyyy-MM-dd
 * @param createdAtUtc  创建时刻，epoch 毫秒，UTC
 * @param terminalAtUtc 确认时刻，epoch 毫秒，UTC
 */
public record ConfirmedReservationView(
        String reservationId,
        String campaignId,
        String visitorId,
        LocalDate utcDate,
        long createdAtUtc,
        long terminalAtUtc
) {
    public static ConfirmedReservationView from(Reservation reservation) {
        return new ConfirmedReservationView(
                reservation.reservationId(),
                reservation.campaignId(),
                reservation.visitorId(),
                reservation.utcDate().toLocalDate(),
                reservation.createdAtUtc(),
                reservation.terminalAtUtc() == null ? 0L : reservation.terminalAtUtc());
    }
}
