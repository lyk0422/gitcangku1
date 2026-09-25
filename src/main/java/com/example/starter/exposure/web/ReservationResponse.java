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
 * @param createdAtUtc    创建时刻，epoch 毫秒，UTC
 * @param expiresAtUtc    到期时刻（创建 + 60 秒），epoch 毫秒，UTC
 * @param terminalAtUtc   终态时刻，未进入终态为 null
 * @param consentDecision 创建时固化的同意决定（ALLOW/DENY）；该公告未启用同意裁决时为 null
 * @param consentVersion  创建时命中的同意版本号；撤回与类别变更均不改变该快照；未启用时为 null
 */
public record ReservationResponse(
        String reservationId,
        String campaignId,
        String visitorId,
        LocalDate utcDate,
        ReservationStatus status,
        long createdAtUtc,
        long expiresAtUtc,
        Long terminalAtUtc,
        String consentDecision,
        Integer consentVersion
) {
    public static ReservationResponse from(Reservation r) {
        return new ReservationResponse(
                r.reservationId(),
                r.campaignId(),
                r.visitorId(),
                r.utcDate().toLocalDate(),
                r.status(),
                r.createdAtUtc(),
                r.expiresAtUtc(),
                r.terminalAtUtc(),
                r.consentDecision() == null ? null : r.consentDecision().name(),
                r.consentVersion());
    }
}
