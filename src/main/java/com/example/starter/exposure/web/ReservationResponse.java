package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;

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
 * @param activityId    预占时公告所属活动编号，固化不随迁移变化；无活动为 null
 * @param hourOfDay     预占所属 UTC 小时（0~23），固化；无活动为 null
 */
public record ReservationResponse(
        String reservationId,
        String campaignId,
        String visitorId,
        LocalDate utcDate,
        ReservationStatus status,
        long createdAtUtc,
        long expiresAtUtc,
        Long terminalAtUtc,
        String activityId,
        Integer hourOfDay
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
                r.hourOfDay());
    }
}
