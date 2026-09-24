package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.ApplyOutcome;
import com.example.starter.exposure.domain.CampaignCategory;
import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;

import java.time.LocalDate;

/**
 * 申请曝光裁决视图：可能为预占成功（RESERVED）或静默抑制（SUPPRESSED）。
 *
 * <p>RESERVED 时预占字段有值、{@code quietUntilUtc} 为 null；
 * SUPPRESSED 时仅裁决字段与 {@code quietUntilUtc} 有值，预占字段为 null，
 * 且系统未创建预占、未写额度账目。</p>
 *
 * @param outcome       裁决结果 RESERVED/SUPPRESSED
 * @param reservationId 预占单编号；SUPPRESSED 时为 null
 * @param campaignId    公告编号
 * @param visitorId     访客编号
 * @param category      公告类别 CRITICAL/SERVICE/MARKETING
 * @param utcDate       额度所属/抑制发生的 UTC 日，格式 yyyy-MM-dd
 * @param status        预占状态；SUPPRESSED 时为 null
 * @param createdAtUtc  预占创建时刻，epoch 毫秒，UTC；SUPPRESSED 时为 null
 * @param expiresAtUtc  预占到期时刻，epoch 毫秒，UTC；SUPPRESSED 时为 null
 * @param quietUntilUtc 静默结束 UTC 时刻，epoch 毫秒；RESERVED 时为 null
 */
public record ApplyResponse(
        ApplyOutcome outcome,
        String reservationId,
        String campaignId,
        String visitorId,
        CampaignCategory category,
        LocalDate utcDate,
        ReservationStatus status,
        Long createdAtUtc,
        Long expiresAtUtc,
        Long quietUntilUtc
) {
    public static ApplyResponse reserved(Reservation r, CampaignCategory category) {
        return new ApplyResponse(
                ApplyOutcome.RESERVED,
                r.reservationId(),
                r.campaignId(),
                r.visitorId(),
                category,
                r.utcDate().toLocalDate(),
                r.status(),
                r.createdAtUtc(),
                r.expiresAtUtc(),
                null);
    }

    public static ApplyResponse suppressed(String campaignId, String visitorId,
                                           CampaignCategory category, LocalDate utcDate,
                                           long quietUntilUtc) {
        return new ApplyResponse(
                ApplyOutcome.SUPPRESSED,
                null,
                campaignId,
                visitorId,
                category,
                utcDate,
                null,
                null,
                null,
                quietUntilUtc);
    }
}
