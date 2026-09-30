package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.Reservation;

import java.time.LocalDate;

/**
 * 预占单视图。
 *
 * @param reservationId 预占单编号
 * @param campaignId    所属公告编号
 * @param visitorId     合成访客编号
 * @param utcDate       额度所属 UTC 日，格式 yyyy-MM-dd；固定为申请时的 UTC 日期
 * @param status        预占状态
 * @param createdAtUtc  创建时刻，epoch 毫秒，UTC
 * @param expiresAtUtc  到期时刻（创建 + 60 秒），epoch 毫秒，UTC
 * @param terminalAtUtc 终态时刻，未进入终态为 null
 * @param activityId    申请时固化的活动编号；历史预占（无活动申请）为 null
 * @param utcHour       申请时固化的 UTC 小时序号（0～23，区间左闭右开）；历史预占为 null
 */
public record ReservationResponse(
        String reservationId,
        String campaignId,
        String visitorId,
        LocalDate utcDate,
        com.example.starter.exposure.domain.ReservationStatus status,
        long createdAtUtc,
        long expiresAtUtc,
        Long terminalAtUtc,
        String activityId,
        Integer utcHour
) {
    public static ReservationResponse from(Reservation r) {
        return new ReservationResponse(
                r.reservationId(),
                r.campaignId(),
                r.visitorId(),
                r.utcDate().toLocalDate(),
                r.status(),
                r.createdAtUtc(),
                r.expiresAtUtc(),
                r.terminalAtUtc(),
                r.activityId(),
                r.utcHour());
    }
}
