package com.example.starter.exposure.web;

import java.time.LocalDate;
import java.util.List;

/**
 * 三层额度视图。按 公告/访客/展示位/UTC 日查询。
 *
 * <p>旧查询（不提供 placementCode）保持原有字段兼容：前 10 个字段语义与旧版一致，
 * 新增字段（展示位层与预占明细）以追加方式输出，不改变旧字段顺序与含义。</p>
 *
 * @param campaignId          公告编号
 * @param visitorId           访客编号；仅查公告总额时为 null
 * @param utcDate             查询的 UTC 日，格式 yyyy-MM-dd
 * @param dailyTotalCap       公告当日总额度，单位次
 * @param usedTotal           公告当日已占用额度（全部展示位的 RESERVED 与 CONFIRMED 合计），单位次
 * @param remainingTotal      公告当日剩余总额度，单位次
 * @param perVisitorDailyCap  该访客当日跨展示位共享上限；仅查总额时为 null
 * @param usedVisitor         该访客当日已占用额度（跨全部展示位合计）；仅查总额时为 null
 * @param remainingVisitor    该访客当日剩余额度；仅查总额时为 null
 * @param settledAtUtc        查询前结算过期预占所用的当前时刻，epoch 毫秒，UTC
 * @param placementCode       查询的展示位编号；不按展示位过滤时为 null
 * @param placementDailyCap   该展示位当日额度；未指定展示位时为 null
 * @param usedPlacement       该展示位当日已占用额度；未指定展示位时为 null
 * @param remainingPlacement  该展示位当日剩余额度；未指定展示位时为 null
 * @param reservations        该访客当日各展示位未释放（RESERVED/CONFIRMED）预占明细；
 *                            仅在按访客查询时返回，否则为空列表
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
        long settledAtUtc,
        String placementCode,
        Integer placementDailyCap,
        Integer usedPlacement,
        Integer remainingPlacement,
        List<ReservationDetail> reservations
) {
    /**
     * 预占展示位明细。
     *
     * @param reservationId 预占单编号
     * @param placementCode 预占固定的展示位编号
     * @param status        预占状态（RESERVED 或 CONFIRMED）
     * @param createdAtUtc  创建时刻，epoch 毫秒，UTC
     * @param expiresAtUtc  到期时刻，epoch 毫秒，UTC
     */
    public record ReservationDetail(
            String reservationId,
            String placementCode,
            String status,
            long createdAtUtc,
            long expiresAtUtc
    ) {
    }
}
