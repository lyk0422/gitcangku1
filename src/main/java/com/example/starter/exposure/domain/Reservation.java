package com.example.starter.exposure.domain;

/**
 * 曝光预占单 PO。额度所属日（{@link #utcDate}）固定为申请时刻的 UTC 日期，
 * 确认跨日也不迁移计数。轮换申请的创意与轮换序号在申请时冻结，
 * 之后查询、重放均按冻结值返回，不回写当前应轮换到的创意。
 *
 * @param reservationId 预占单编号
 * @param campaignId    所属公告编号
 * @param visitorId     合成访客编号
 * @param utcDate       额度所属 UTC 日（java.time.LocalDate 对应的 java.sql.Date）
 * @param status        预占状态
 * @param applyType     申请类型：NORMAL 普通 / ROTATION 轮换
 * @param creativeId    冻结的轮换创意编号；普通申请为 null
 * @param rotationSeq   冻结的轮换序号（申请时刻该访客已确认轮换数）；普通申请为 null
 * @param version       乐观版本号，自 0 起，每次进入终态 +1
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
        ApplyType applyType,
        String creativeId,
        Integer rotationSeq,
        int version,
        long createdAtUtc,
        long expiresAtUtc,
        Long terminalAtUtc
) {
}
