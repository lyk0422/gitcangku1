package com.example.starter.api.dto;

/**
 * 走廊时段预约结果。时段为 UTC 左闭右开 [startTime, endTime)。
 *
 * @param reservationId 预约唯一标识（不可变）
 * @param corridorId    所属走廊标识
 * @param reviewId      关联的已 CLEAR 审核结果标识（仅引用）
 * @param routeId       关联审核对应的航线标识
 * @param startTime     时段起始（含），ISO-8601 UTC
 * @param endTime       时段结束（不含），ISO-8601 UTC
 * @param status        ACTIVE 生效中 / CANCELLED 已取消（历史保留）
 */
public record ReservationResult(String reservationId, String corridorId, String reviewId,
                                String routeId, String startTime, String endTime,
                                String status) {
}
