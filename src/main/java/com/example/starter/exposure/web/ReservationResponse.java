package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;

import java.time.LocalDate;

/**
 * 预占单视图。申请（apply）成功响应中冻结申请时刻的三套剩余额度快照；
 * 幂等重放返回首次响应原文，窗口或日额度随后变化不会反映到旧键响应中。
 *
 * @param reservationId     预占单编号
 * @param campaignId        所属公告编号
 * @param visitorId         合成访客编号
 * @param utcDate           额度所属 UTC 日，格式 yyyy-MM-dd；固定为申请时的 UTC 日期
 * @param status            预占状态
 * @param createdAtUtc      创建时刻，epoch 毫秒，UTC；权重窗口归属时刻固定为该值，确认不刷新
 * @param expiresAtUtc      到期时刻（创建 + 60 秒），epoch 毫秒，UTC
 * @param terminalAtUtc     终态时刻，未进入终态为 null
 * @param remainingTotal    申请时刻冻结的公告当日剩余总额度，单位次；非申请响应为 null
 * @param remainingVisitor  申请时刻冻结的该访客当日剩余额度，单位次；非申请响应为 null
 * @param remainingWeight   申请时刻冻结的该访客滚动窗口剩余权重，单位权重点；
 *                          旧类型公告或非申请响应为 null
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
        Integer remainingTotal,
        Integer remainingVisitor,
        Integer remainingWeight
) {
    /** 状态查询/确认/取消响应：不含申请时刻的额度快照。 */
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
                null, null, null);
    }

    /** 申请成功响应：冻结申请时刻的三套剩余额度。 */
    public static ReservationResponse from(Reservation r, int remainingTotal,
                                           int remainingVisitor, Integer remainingWeight) {
        return new ReservationResponse(
                r.reservationId(),
                r.campaignId(),
                r.visitorId(),
                r.utcDate().toLocalDate(),
                r.status(),
                r.createdAtUtc(),
                r.expiresAtUtc(),
                r.terminalAtUtc(),
                remainingTotal, remainingVisitor, remainingWeight);
    }
}
