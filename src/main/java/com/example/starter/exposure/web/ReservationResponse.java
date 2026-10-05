package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;

import java.time.LocalDate;

/**
 * 预占单视图。申请（apply）的首次响应会冻结申请时刻的三套额度快照
 * （remainingTotal/remainingVisitor/remainingWeight），同键重放返回该冻结值，
 * 不随窗口或日额度后续变化而更新；确认/取消/查询明细不携带快照，三个快照字段为 null。
 *
 * @param reservationId    预占单编号
 * @param campaignId       所属公告编号
 * @param visitorId        合成访客编号
 * @param utcDate          额度所属 UTC 日，格式 yyyy-MM-dd；固定为申请时的 UTC 日期
 * @param status           预占状态
 * @param createdAtUtc     创建时刻，epoch 毫秒，UTC；滚动权重窗口归属时刻固定为该值，确认不刷新
 * @param expiresAtUtc     到期时刻（创建 + 60 秒），epoch 毫秒，UTC
 * @param terminalAtUtc    终态时刻，未进入终态为 null
 * @param remainingTotal   申请成功后公告当日剩余总额度快照，单位次；非申请响应为 null
 * @param remainingVisitor 申请成功后该访客当日剩余额度快照，单位次；非申请响应为 null
 * @param remainingWeight  申请成功后该访客滚动窗口剩余权重快照；旧类型公告或非申请响应为 null
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
    /**
     * 无额度快照的视图（确认/取消/明细查询）。
     */
    public static ReservationResponse from(Reservation r) {
        return from(r, null, null, null);
    }

    /**
     * 申请响应：冻结申请时刻的三套额度快照。
     */
    public static ReservationResponse from(Reservation r, Integer remainingTotal,
                                           Integer remainingVisitor, Integer remainingWeight) {
        return new ReservationResponse(
                r.reservationId(),
                r.campaignId(),
                r.visitorId(),
                r.utcDate().toLocalDate(),
                r.status(),
                r.createdAtUtc(),
                r.expiresAtUtc(),
                r.terminalAtUtc(),
                remainingTotal,
                remainingVisitor,
                remainingWeight);
    }
}
