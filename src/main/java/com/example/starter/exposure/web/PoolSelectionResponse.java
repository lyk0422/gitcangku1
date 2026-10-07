package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;

import java.time.LocalDate;
import java.util.Map;

/**
 * 池轮转选择结果视图。选择时冻结成员缺额与公告身份，旧重放返回首次完整响应。
 *
 * @param reservationId 预占单编号
 * @param poolId        选择池编号
 * @param campaignId    选中的公告编号
 * @param visitorId     合成访客编号
 * @param utcDate       额度所属 UTC 日，格式 yyyy-MM-dd；固定为选择时的 UTC 日期
 * @param status        预占状态
 * @param createdAtUtc  选择时刻，epoch 毫秒，UTC
 * @param expiresAtUtc  到期时刻（选择 + 60 秒），epoch 毫秒，UTC
 * @param terminalAtUtc 终态时刻，未进入终态为 null
 * @param deficits      选择后各成员的临时缺额（公告编号 -&gt; 缺额），仅确认时正式提交
 */
public record PoolSelectionResponse(
        String reservationId,
        String poolId,
        String campaignId,
        String visitorId,
        LocalDate utcDate,
        ReservationStatus status,
        long createdAtUtc,
        long expiresAtUtc,
        Long terminalAtUtc,
        Map<String, Long> deficits
) {
    public static PoolSelectionResponse from(Reservation reservation, String poolId,
                                             Map<String, Long> deficits) {
        return new PoolSelectionResponse(
                reservation.reservationId(),
                poolId,
                reservation.campaignId(),
                reservation.visitorId(),
                reservation.utcDate().toLocalDate(),
                reservation.status(),
                reservation.createdAtUtc(),
                reservation.expiresAtUtc(),
                reservation.terminalAtUtc(),
                deficits);
    }
}
