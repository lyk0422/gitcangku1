package com.example.starter.api.dto;

/**
 * 预约结果视图（创建响应、占用列表与历史查询共用）。
 *
 * @param reservationKey 预约全局唯一业务键
 * @param corridorId     所属走廊标识
 * @param startTime      开始时刻，epoch 毫秒（UTC），含
 * @param endTime        结束时刻，epoch 毫秒（UTC），不含
 * @param reviewId       关联的 CLEAR 审核结果标识
 * @param status         ACTIVE / CANCELLED
 * @param createdAt      创建时间，epoch 毫秒（UTC）
 * @param cancelledAt    取消时间，epoch 毫秒（UTC）；未取消为 null
 */
public record ReservationResultDto(String reservationKey, String corridorId, long startTime,
                                   long endTime, String reviewId, String status,
                                   long createdAt, Long cancelledAt) {
}
