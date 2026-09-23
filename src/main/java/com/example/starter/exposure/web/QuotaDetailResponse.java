package com.example.starter.exposure.web;

import java.util.List;

/**
 * 三层额度及预占展示位明细视图。
 *
 * @param quota        三层额度视图（placementCode 为 null 时展示位字段也为 null）
 * @param reservations 匹配查询维度（公告/访客/展示位/UTC 日）的预占单明细；
 *                     未指定的维度不过滤，按申请展示位原样列出
 */
public record QuotaDetailResponse(
        QuotaResponse quota,
        List<ReservationResponse> reservations
) {
}
