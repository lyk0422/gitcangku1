package com.example.starter.exposure.domain;

/**
 * 组合预占组 PO。一次组合申请包含 2～5 个不同公告的成员预占单，
 * 整组共用同一到期时刻（申请时刻 + 60 秒）与同一申请 UTC 日；
 * 确认/取消/过期均以整组为单位一次完成，额度按成员逐个占用/释放。
 *
 * @param bundleId      组合预占组编号
 * @param visitorId     合成访客编号
 * @param utcDate       额度所属 UTC 日（java.time.LocalDate 对应的 java.sql.Date），跨日不迁移
 * @param status        组状态（RESERVED/CONFIRMED/CANCELLED/EXPIRED）
 * @param version       乐观锁版本，初始 0，每次进入终态 +1；确认/取消需携带期望版本
 * @param createdAtUtc  创建（申请）时刻，epoch 毫秒，UTC
 * @param expiresAtUtc  到期时刻，创建时刻 + 60 秒，epoch 毫秒，UTC；当前时刻达到该值即整组过期
 * @param terminalAtUtc 进入终态的时刻，未到终态为 null
 */
public record ReservationBundle(
        String bundleId,
        String visitorId,
        java.sql.Date utcDate,
        ReservationStatus status,
        long version,
        long createdAtUtc,
        long expiresAtUtc,
        Long terminalAtUtc
) {
}
