package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationGroup;
import com.example.starter.exposure.domain.ReservationStatus;

import java.time.LocalDate;
import java.util.List;

/**
 * 组合预占组视图。成员按公告编号排序，与组共用到期时刻与 UTC 日。
 *
 * @param groupId       组合组编号
 * @param visitorId     合成访客编号
 * @param utcDate       额度所属 UTC 日，格式 yyyy-MM-dd；固定为申请时的 UTC 日期
 * @param status        组状态（RESERVED/CONFIRMED/CANCELLED/EXPIRED）
 * @param createdAtUtc  创建时刻，epoch 毫秒，UTC
 * @param expiresAtUtc  到期时刻（创建 + 60 秒），epoch 毫秒，UTC
 * @param terminalAtUtc 终态时刻，未进入终态为 null
 * @param members       成员预占单完整视图（每条各占原公告及访客两级额度）
 */
public record BundleResponse(
        String groupId,
        String visitorId,
        LocalDate utcDate,
        ReservationStatus status,
        long createdAtUtc,
        long expiresAtUtc,
        Long terminalAtUtc,
        List<ReservationResponse> members
) {
    public static BundleResponse from(ReservationGroup group, List<Reservation> members) {
        return new BundleResponse(
                group.groupId(),
                group.visitorId(),
                group.utcDate().toLocalDate(),
                group.status(),
                group.createdAtUtc(),
                group.expiresAtUtc(),
                group.terminalAtUtc(),
                members.stream().map(ReservationResponse::from).toList());
    }
}
