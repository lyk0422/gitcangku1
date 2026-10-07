package com.example.starter.exposure.domain;

/**
 * 组合预占组 PO。一次组合申请（2～5 个不同公告）对应一组，
 * 整组共用申请时刻的 UTC 日（{@link #utcDate}）与到期时刻（创建 + 60 秒），
 * 确认/取消/过期均整组一次转换，成员额度各自按公告与访客两级结算。
 *
 * @param groupId       组合组编号，全局唯一
 * @param visitorId     合成访客编号（整组同一访客）
 * @param utcDate       额度所属 UTC 日（java.time.LocalDate 对应的 java.sql.Date），跨日不迁移
 * @param status        组状态，与单条预占同一状态机
 * @param createdAtUtc  创建（申请）时刻，epoch 毫秒，UTC
 * @param expiresAtUtc  到期时刻，创建时刻 + 60 秒，epoch 毫秒，UTC；当前时刻达到该值即整组过期
 * @param terminalAtUtc 进入终态（CONFIRMED/CANCELLED/EXPIRED）的时刻，未到终态为 null
 */
public record ReservationGroup(
        String groupId,
        String visitorId,
        java.sql.Date utcDate,
        ReservationStatus status,
        long createdAtUtc,
        long expiresAtUtc,
        Long terminalAtUtc
) {
}
