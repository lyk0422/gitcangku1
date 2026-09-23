package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;

import java.time.LocalDate;

/**
 * 预占展示位明细项：额度查询中返回的当日活跃（RESERVED/CONFIRMED）预占。
 *
 * @param reservationId 预占单编号
 * @param placementCode 预占固定的展示位编号
 * @param visitorId     访客编号
 * @param utcDate       额度所属 UTC 日，固定为申请日
 * @param status        预占状态（RESERVED 或 CONFIRMED）
 * @param createdAtUtc  创建时刻，epoch 毫秒，UTC
 * @param expiresAtUtc  到期时刻，epoch 毫秒，UTC
 */
public record ReservationPlacementDetail(
        String reservationId,
        String placementCode,
        String visitorId,
        LocalDate utcDate,
        ReservationStatus status,
        long createdAtUtc,
        long expiresAtUtc
) {
    public static ReservationPlacementDetail from(Reservation r) {
        return new ReservationPlacementDetail(
                r.reservationId(),
                r.placementCode(),
                r.visitorId(),
                r.utcDate().toLocalDate(),
                r.status(),
                r.createdAtUtc(),
                r.expiresAtUtc());
    }
}
