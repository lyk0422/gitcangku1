package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;

import java.time.LocalDate;

/**
 * 预占单视图。
 *
 * @param reservationId   预占单编号
 * @param campaignId      所属公告编号
 * @param visitorId       合成访客编号
 * @param utcDate         额度所属 UTC 日，格式 yyyy-MM-dd；固定为申请时的 UTC 日期
 * @param status          预占状态
 * @param category        创建时固化的活动类别
 * @param consentId       创建时命中的同意记录编号快照
 * @param consentVersion  创建时命中的同意版本快照；之后撤回或类别修改不改变本单
 * @param consentDecision 创建时命中的同意决定快照
 * @param createdAtUtc    创建时刻，epoch 毫秒，UTC
 * @param expiresAtUtc    到期时刻（创建 + 60 秒），epoch 毫秒，UTC
 * @param terminalAtUtc   终态时刻，未进入终态为 null
 */
public record ReservationResponse(
        String reservationId,
        String campaignId,
        String visitorId,
        LocalDate utcDate,
        ReservationStatus status,
        String category,
        String consentId,
        Long consentVersion,
        String consentDecision,
        long createdAtUtc,
        long expiresAtUtc,
        Long terminalAtUtc
) {
    public static ReservationResponse from(Reservation r) {
        return new ReservationResponse(
                r.reservationId(),
                r.campaignId(),
                r.visitorId(),
                r.utcDate().toLocalDate(),
                r.status(),
                r.category(),
                r.consentId(),
                r.consentVersion(),
                r.consentDecision() == null ? null : r.consentDecision().name(),
                r.createdAtUtc(),
                r.expiresAtUtc(),
                r.terminalAtUtc());
    }
}
