package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;

import java.time.LocalDate;

/**
 * 额度视图。按 公告/访客/展示位/UTC 日查询三层额度；访客、展示位维度为空时
 * 对应层的容量字段为 null。旧响应字段（campaignId…remainingVisitor、settledAtUtc）
 * 保持不变，新增字段追加在末尾区域以兼容旧调用方。
 *
 * @param campaignId          公告编号
 * @param visitorId           访客编号；不按访客过滤时为 null
 * @param utcDate             查询的 UTC 日，格式 yyyy-MM-dd
 * @param dailyTotalCap       公告当日总额度，单位次
 * @param usedTotal           公告当日已占用额度（RESERVED 与 CONFIRMED 合计），单位次
 * @param remainingTotal      公告当日剩余总额度，单位次
 * @param perVisitorDailyCap  该访客当日跨全部展示位共享上限；不按访客过滤时为 null
 * @param usedVisitor         该访客当日跨全部展示位已占用额度；不按访客过滤时为 null
 * @param remainingVisitor    该访客当日剩余共享额度；不按访客过滤时为 null
 * @param placementCode       展示位编号；不按展示位过滤时为 null
 * @param placementDailyCap   该展示位当日额度；不按展示位过滤时为 null
 * @param usedPlacement       该展示位当日已占用额度；不按展示位过滤时为 null
 * @param remainingPlacement  该展示位当日剩余额度；不按展示位过滤时为 null
 * @param configVersion       公告当前展示位配置版本号
 * @param reservations        按所给维度过滤后、当前仍占用额度的预占展示位明细
 * @param settledAtUtc        查询前结算过期预占所用的当前时刻，epoch 毫秒，UTC
 */
public record QuotaResponse(
        String campaignId,
        String visitorId,
        LocalDate utcDate,
        int dailyTotalCap,
        int usedTotal,
        int remainingTotal,
        Integer perVisitorDailyCap,
        Integer usedVisitor,
        Integer remainingVisitor,
        String placementCode,
        Integer placementDailyCap,
        Integer usedPlacement,
        Integer remainingPlacement,
        int configVersion,
        java.util.List<ReservationDetail> reservations,
        long settledAtUtc
) {
    /**
     * 预占展示位明细：仅包含结算口径下仍占用额度（RESERVED/CONFIRMED）的预占。
     *
     * @param reservationId 预占单编号
     * @param placementCode 预占固定的展示位编号
     * @param visitorId     访客编号
     * @param status        预占状态
     * @param createdAtUtc  创建时刻，epoch 毫秒，UTC
     * @param expiresAtUtc  到期时刻，epoch 毫秒，UTC
     * @param terminalAtUtc 终态时刻；RESERVED 时为 null
     */
    public record ReservationDetail(
            String reservationId,
            String placementCode,
            String visitorId,
            ReservationStatus status,
            long createdAtUtc,
            long expiresAtUtc,
            Long terminalAtUtc
    ) {
        public static ReservationDetail from(Reservation r) {
            return new ReservationDetail(
                    r.reservationId(),
                    r.placementCode(),
                    r.visitorId(),
                    r.status(),
                    r.createdAtUtc(),
                    r.expiresAtUtc(),
                    r.terminalAtUtc());
        }
    }
}
