package com.example.starter.exposure.domain;

/**
 * 曝光预占单 PO。额度所属日（{@link #utcDate}）固定为申请时刻的 UTC 日期，
 * 确认跨日也不迁移计数。
 *
 * @param reservationId   预占单编号
 * @param campaignId      所属公告编号
 * @param visitorId       合成访客编号
 * @param utcDate         额度所属 UTC 日（java.time.LocalDate 对应的 java.sql.Date）
 * @param status          预占状态
 * @param category        创建时公告所属活动类别快照；之后类别修改不影响本单
 * @param consentId       创建时命中的同意记录编号快照；无有效同意导致的拒绝不会建单，故建单时非 null
 * @param consentVersion  创建时命中的同意版本快照；撤回/改类别后仍按本版本随回执固化结算
 * @param consentDecision 创建时命中的同意决定快照（ALLOW）
 * @param createdAtUtc    创建（申请）时刻，epoch 毫秒，UTC
 * @param expiresAtUtc    到期时刻，创建时刻 + 60 秒，epoch 毫秒，UTC；当前时刻达到该值即过期
 * @param terminalAtUtc   进入终态（CONFIRMED/CANCELLED/EXPIRED）的时刻，未到终态为 null
 */
public record Reservation(
        String reservationId,
        String campaignId,
        String visitorId,
        java.sql.Date utcDate,
        ReservationStatus status,
        String category,
        String consentId,
        Long consentVersion,
        ConsentDecision consentDecision,
        long createdAtUtc,
        long expiresAtUtc,
        Long terminalAtUtc
) {
}
