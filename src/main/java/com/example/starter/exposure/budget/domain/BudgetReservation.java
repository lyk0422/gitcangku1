package com.example.starter.exposure.budget.domain;

import com.example.starter.exposure.domain.ReservationStatus;

/**
 * 预算曝光预占单 PO。{@link #campaignId} 在创建时冻结归属：
 * 预算转移后该预占及其迟到回执始终归原活动，不改挂目标活动。
 *
 * @param reservationId 预占单编号
 * @param campaignId    归属活动编号（创建时冻结）
 * @param visitorId     合成访客编号
 * @param status        预占状态（复用曝光域状态机）
 * @param createdAtUtc  创建（申请）时刻，epoch 毫秒，UTC
 * @param expiresAtUtc  到期时刻，epoch 毫秒，UTC；当前时刻达到该值即过期并释放在途
 * @param terminalAtUtc 进入终态（CONFIRMED/CANCELLED/EXPIRED）的时刻，未到终态为 null
 */
public record BudgetReservation(
        String reservationId,
        String campaignId,
        String visitorId,
        ReservationStatus status,
        long createdAtUtc,
        long expiresAtUtc,
        Long terminalAtUtc
) {
}
