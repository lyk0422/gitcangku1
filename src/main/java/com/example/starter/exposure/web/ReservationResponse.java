package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.ApplyType;
import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;

import java.time.LocalDate;

/**
 * 预占单视图。轮换字段为申请时冻结值，查询不回写当前应轮换到的创意。
 *
 * @param reservationId 预占单编号
 * @param campaignId    所属公告编号
 * @param visitorId     合成访客编号
 * @param utcDate       额度所属 UTC 日，格式 yyyy-MM-dd；固定为申请时的 UTC 日期
 * @param status        预占状态
 * @param applyType     申请类型：NORMAL 普通 / ROTATION 轮换
 * @param creativeId    冻结的轮换创意编号；普通申请为 null
 * @param rotationSeq   冻结的轮换序号；普通申请为 null
 * @param version       乐观版本号，自 0 起，每次进入终态 +1
 * @param createdAtUtc  创建时刻，epoch 毫秒，UTC
 * @param expiresAtUtc  到期时刻（创建 + 60 秒），epoch 毫秒，UTC
 * @param terminalAtUtc 终态时刻，未进入终态为 null
 */
public record ReservationResponse(
        String reservationId,
        String campaignId,
        String visitorId,
        LocalDate utcDate,
        ReservationStatus status,
        ApplyType applyType,
        String creativeId,
        Integer rotationSeq,
        int version,
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
                r.applyType(),
                r.creativeId(),
                r.rotationSeq(),
                r.version(),
                r.createdAtUtc(),
                r.expiresAtUtc(),
                r.terminalAtUtc());
    }
}
