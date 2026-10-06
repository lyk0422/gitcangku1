package com.example.starter.exposure.domain;

/**
 * 曝光预占单 PO。额度所属日（{@link #utcDate}）固定为申请时刻的 UTC 日期，
 * 确认跨日也不迁移计数。
 *
 * @param reservationId 预占单编号
 * @param campaignId    所属公告编号
 * @param visitorId     合成访客编号
 * @param utcDate       额度所属 UTC 日（java.time.LocalDate 对应的 java.sql.Date）
 * @param status        预占状态
 * @param creativeId    轮换预占申请时冻结的创意编号；普通预占为 null，终态后不回写
 * @param rotationSeq   轮换预占申请时冻结的轮换序号（=申请时该访客已确认次数）；普通预占为 null
 * @param createdAtUtc  创建（申请）时刻，epoch 毫秒，UTC
 * @param expiresAtUtc  到期时刻，创建时刻 + 60 秒，epoch 毫秒，UTC；当前时刻达到该值即过期
 * @param terminalAtUtc 进入终态（CONFIRMED/CANCELLED/EXPIRED）的时刻，未到终态为 null
 */
public record Reservation(
        String reservationId,
        String campaignId,
        String visitorId,
        java.sql.Date utcDate,
        ReservationStatus status,
        String creativeId,
        Integer rotationSeq,
        long createdAtUtc,
        long expiresAtUtc,
        Long terminalAtUtc
) {
    /** 是否轮换预占（申请时冻结了创意与轮换序号）。 */
    public boolean isRotation() {
        return creativeId != null;
    }
}
