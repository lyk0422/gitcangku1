package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationBundle;
import com.example.starter.exposure.domain.ReservationStatus;

import java.time.LocalDate;
import java.util.List;

/**
 * 组合预占组视图，含全部成员预占单。
 *
 * @param bundleId      组合预占组编号
 * @param visitorId     合成访客编号
 * @param utcDate       额度所属 UTC 日，格式 yyyy-MM-dd；固定为申请时的 UTC 日期
 * @param status        组状态
 * @param version       当前版本（每次进入终态 +1）
 * @param createdAtUtc  创建时刻，epoch 毫秒，UTC
 * @param expiresAtUtc  到期时刻（创建 + 60 秒），epoch 毫秒，UTC，全组成员共用
 * @param terminalAtUtc 终态时刻，未进入终态为 null
 * @param members       成员预占单，按公告号排序
 */
public record BundleResponse(
        String bundleId,
        String visitorId,
        LocalDate utcDate,
        ReservationStatus status,
        long version,
        long createdAtUtc,
        long expiresAtUtc,
        Long terminalAtUtc,
        List<ReservationResponse> members
) {
    public static BundleResponse from(ReservationBundle bundle, List<Reservation> members) {
        return new BundleResponse(
                bundle.bundleId(),
                bundle.visitorId(),
                bundle.utcDate().toLocalDate(),
                bundle.status(),
                bundle.version(),
                bundle.createdAtUtc(),
                bundle.expiresAtUtc(),
                bundle.terminalAtUtc(),
                members.stream().map(ReservationResponse::from).toList());
    }
}
