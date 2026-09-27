package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.Reservation;

import java.time.LocalDate;

/**
 * 预占单视图。历史字段（reservationId～terminalAtUtc）与排序保持稳定，
 * 归因窗口相关字段追加在后，null 语义不变。
 *
 * @param reservationId              预占单编号
 * @param campaignId                 所属公告编号
 * @param visitorId                  合成访客编号
 * @param utcDate                    额度所属 UTC 日，格式 yyyy-MM-dd；固定为申请时的 UTC 日期
 * @param status                     预占状态
 * @param createdAtUtc               创建时刻（归因窗口起点，含），epoch 毫秒，UTC
 * @param expiresAtUtc               预占到期时刻（创建 + 60 秒），epoch 毫秒，UTC
 * @param terminalAtUtc              终态裁决时刻，未进入终态为 null
 * @param attributionWindowMinutes   申请时快照的归因窗口分钟数（要求值），不可变
 * @param windowVersion              申请时快照的窗口版本号，不可变
 * @param attributedAtUtc            首次计入归因的回执业务时刻，未归因恒为 null，epoch 毫秒，UTC
 * @param attributedRequestId        首次成功归因回执的幂等键，未归因恒为 null
 */
public record ReservationResponse(
        String reservationId,
        String campaignId,
        String visitorId,
        LocalDate utcDate,
        String status,
        long createdAtUtc,
        long expiresAtUtc,
        Long terminalAtUtc,
        int attributionWindowMinutes,
        int windowVersion,
        Long attributedAtUtc,
        String attributedRequestId
) {
    public static ReservationResponse from(Reservation r) {
        return new ReservationResponse(
                r.reservationId(),
                r.campaignId(),
                r.visitorId(),
                r.utcDate().toLocalDate(),
                r.status().name(),
                r.createdAtUtc(),
                r.expiresAtUtc(),
                r.terminalAtUtc(),
                r.attributionWindowMinutes(),
                r.windowVersion(),
                r.attributedAtUtc(),
                r.attributedRequestId());
    }
}
