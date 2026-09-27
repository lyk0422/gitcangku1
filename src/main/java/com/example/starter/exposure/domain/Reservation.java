package com.example.starter.exposure.domain;

/**
 * 曝光预占单 PO。额度所属日（{@link #utcDate}）固定为申请时刻的 UTC 日期，
 * 确认跨日也不迁移计数。归因窗口（版本与分钟数）在申请时刻快照，随后续窗口调整不变。
 *
 * @param reservationId 预占单编号
 * @param campaignId    所属公告编号
 * @param visitorId     合成访客编号
 * @param utcDate       额度所属 UTC 日（java.time.LocalDate 对应的 java.sql.Date）
 * @param status        预占状态
 * @param createdAtUtc  创建（申请）时刻，epoch 毫秒，UTC
 * @param expiresAtUtc  到期时刻，创建时刻 + 60 秒，epoch 毫秒，UTC；当前时刻达到该值即过期
 * @param terminalAtUtc 进入终态（CONFIRMED/CANCELLED/EXPIRED）的时刻，未到终态为 null
 * @param windowVersion 申请时刻的归因窗口版本号快照，不可变
 * @param windowMinutes 申请时刻的归因窗口分钟数快照，不可变
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
        int windowVersion,
        int windowMinutes
) {
}
