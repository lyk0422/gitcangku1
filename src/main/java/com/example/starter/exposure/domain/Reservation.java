package com.example.starter.exposure.domain;

/**
 * 曝光预占单 PO。额度所属日（{@link #utcDate}）固定为申请时刻的 UTC 日期，
 * 确认/归因跨日也不迁移计数。归因窗口（{@link #attributionWindowMinutes}、
 * {@link #windowVersion}）在创建时快照，后续公告改窗口不影响本单。
 *
 * @param reservationId              预占单编号
 * @param campaignId                 所属公告编号
 * @param visitorId                  合成访客编号
 * @param utcDate                    额度所属 UTC 日（java.time.LocalDate 对应的 java.sql.Date）
 * @param status                     预占状态
 * @param createdAtUtc               创建（申请）时刻，即归因窗口起点（含），epoch 毫秒，UTC
 * @param expiresAtUtc               预占到期时刻，创建时刻 + 60 秒，达到即过期，epoch 毫秒，UTC
 * @param terminalAtUtc              进入终态（CONFIRMED/ATTRIBUTED/CANCELLED/EXPIRED）的服务端裁决时刻，未到终态为 null
 * @param attributionWindowMinutes   申请时快照的归因窗口分钟数，不可变
 * @param windowVersion              申请时快照的窗口版本号，不可变
 * @param attributedAtUtc            首次计入归因的回执业务时刻，未归因恒为 null，epoch 毫秒，UTC
 * @param attributedRequestId        首次成功归因回执的幂等键，未归因恒为 null
 */
public record Reservation(
        String reservationId,
        String campaignId,
        String visitorId,
        java.sql.Date utcDate,
        ReservationStatus status,
        long createdAtUtc,
        long expiresAtUtc,
        Long terminalAtUtc,
        int attributionWindowMinutes,
        int windowVersion,
        Long attributedAtUtc,
        String attributedRequestId
) {
}
