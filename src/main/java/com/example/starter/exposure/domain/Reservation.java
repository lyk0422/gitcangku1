package com.example.starter.exposure.domain;

/**
 * 曝光预占单 PO。额度所属日（{@link #utcDate}）固定为申请时刻的 UTC 日期，
 * 确认跨日也不迁移计数。活动与小时归属在申请时固化，后续活动迁移不改写。
 *
 * @param reservationId 预占单编号
 * @param campaignId    所属公告编号
 * @param visitorId     合成访客编号
 * @param utcDate       额度所属 UTC 日（java.time.LocalDate 对应的 java.sql.Date）
 * @param status        预占状态
 * @param createdAtUtc  创建（申请）时刻，epoch 毫秒，UTC
 * @param expiresAtUtc  到期时刻，创建时刻 + 60 秒，epoch 毫秒，UTC；当前时刻达到该值即过期
 * @param terminalAtUtc 进入终态（CONFIRMED/CANCELLED/EXPIRED）的时刻，未到达终态为 null
 * @param activityId    申请时公告所属活动编号；公告未归入活动为 null，固化不随迁移改变
 * @param hourOfDay     申请时刻的 UTC 小时（0~23），区间左闭右开；无活动时为 null
 */
public record Reservation(
        String reservationId,
        String campaignId,
        String visitorId,
        java.sql.Date utcDate,
        ReservationStatus status,
        long createdAtUtc,
        long expiresAtUtc,
        Long terminalAtUtc,
        String activityId,
        Integer hourOfDay
) {
}