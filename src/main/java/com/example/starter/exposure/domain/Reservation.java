package com.example.starter.exposure.domain;

/**
 * 曝光预占单 PO。额度所属日（{@link #utcDate}）固定为申请时刻的 UTC 日期，
 * 确认跨日也不迁移计数。{@link #activityId} 与 {@link #utcHour} 在申请时从公告当前
 * 活动固化，之后活动迁移或配置修改都不改写本单；无活动归属的历史单两者为 {@code null}。
 *
 * @param reservationId 预占单编号
 * @param campaignId    所属公告编号
 * @param visitorId     合成访客编号
 * @param utcDate       额度所属 UTC 日（java.time.LocalDate 对应的 java.sql.Date）
 * @param activityId    申请时固化的活动编号；{@code null} 为无活动归属的历史单
 * @param utcHour       申请时固化的 UTC 小时（0～23，左闭右开）；{@code null} 为无活动归属的历史单
 * @param status        预占状态
 * @param createdAtUtc  创建（申请）时刻，epoch 毫秒，UTC
 * @param expiresAtUtc  到期时刻，创建时刻 + 60 秒，epoch 毫秒，UTC；当前时刻达到该值即过期
 * @param terminalAtUtc 进入终态（CONFIRMED/CANCELLED/EXPIRED）的时刻，未到终态为 null
 */
public record Reservation(
        String reservationId,
        String campaignId,
        String visitorId,
        java.sql.Date utcDate,
        String activityId,
        Integer utcHour,
        ReservationStatus status,
        long createdAtUtc,
        long expiresAtUtc,
        Long terminalAtUtc
) {
}
