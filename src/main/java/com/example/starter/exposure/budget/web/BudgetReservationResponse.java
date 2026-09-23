package com.example.starter.exposure.budget.web;

import com.example.starter.exposure.budget.domain.BudgetReservation;
import com.example.starter.exposure.domain.ReservationStatus;

/**
 * 预算预占单视图。campaignId 为创建时冻结的归属活动。
 *
 * @param reservationId 预占单编号
 * @param campaignId    归属活动编号（创建时冻结）
 * @param visitorId     合成访客编号
 * @param status        预占状态
 * @param createdAtUtc  创建时刻，epoch 毫秒，UTC
 * @param expiresAtUtc  到期时刻（创建 + 60 秒），epoch 毫秒，UTC
 * @param terminalAtUtc 终态时刻，未进入终态为 null
 */
public record BudgetReservationResponse(
        String reservationId,
        String campaignId,
        String visitorId,
        ReservationStatus status,
        long createdAtUtc,
        long expiresAtUtc,
        Long terminalAtUtc
) {
    public static BudgetReservationResponse from(BudgetReservation r) {
        return new BudgetReservationResponse(
                r.reservationId(),
                r.campaignId(),
                r.visitorId(),
                r.status(),
                r.createdAtUtc(),
                r.expiresAtUtc(),
                r.terminalAtUtc());
    }
}
