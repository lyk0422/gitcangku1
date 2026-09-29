package com.example.starter.exposure.domain;

/**
 * 曝光预占单 PO。额度所属日（{@link #utcDate}）固定为申请时刻的 UTC 日期，
 * 确认跨日也不迁移计数；{@link #activityId} 与 {@link #utcHour} 固化申请时的
 * 活动归属与 UTC 小时，公告事后迁移活动不改变本单，取消/过期只释放该固化小时。
 *
 * @param reservationId 预占单编号
 * @param campaignId    所属公告编号
 * @param visitorId     合成访客编号
 * @param activityId    申请时固化的活动编号；公告当时未归属活动为 null
 * @param utcDate       额度所属 UTC 日（java.time.LocalDate 对应的 java.sql.Date）
 * @param utcHour       申请时固化的 UTC 小时（0～23）；无活动归属的历史单为 null
 * @param status        预占状态
 * @param createdAtUtc  创建（申请）时刻，epoch 毫秒，UTC
 * @param expiresAtUtc  到期时刻，创建时刻 + 60 秒，epoch 毫秒，UTC；当前时刻达到该值即过期
 * @param terminalAtUtc 进入终态（CONFIRMED/CANCELLED/EXPIRED）的时刻，未到终态为 null
 */
public record Reservation(
        String reservationId,
        String campaignId,
        String visitorId,
        String activityId,
        java.sql.Date utcDate,
        Integer utcHour,
        ReservationStatus status,
        long createdAtUtc,
        long expiresAtUtc,
        Long terminalAtUtc
) {
}
