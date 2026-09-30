package com.example.starter.exposure.web;

import java.util.List;

/**
 * 预占历史视图（只读）。按创建时刻升序、预占单编号升序稳定排序，
 * 时间精度与字段 null 语义与预占明细一致。
 *
 * @param total 返回条数（实际值）
 * @param limit 本次查询要求的最大条数（要求值）
 * @param items 预占明细列表
 */
public record ReservationHistoryResponse(
        int total,
        int limit,
        List<ReservationResponse> items
) {
}
