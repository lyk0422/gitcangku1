package com.example.starter.repo;

/**
 * 走廊时段预约持久化记录。时段为 UTC 左闭右开 [startMillis, endMillis)。
 *
 * @param reservationId  预约唯一标识（不可变）
 * @param corridorId     所属走廊标识
 * @param reviewId       关联的已 CLEAR 审核结果标识（仅引用，不消费）
 * @param routeId        关联审核对应的航线标识
 * @param startMillis    时段起始（含），epoch 毫秒（UTC）
 * @param endMillis      时段结束（不含），epoch 毫秒（UTC）
 * @param status         ACTIVE / CANCELLED
 * @param reservationKey 预约幂等键（全局唯一）
 * @param createdAt      创建时间（epoch 毫秒，UTC）
 * @param cancelledAt    取消时间（epoch 毫秒，UTC）；null 表示未取消
 */
public record ReservationPo(String reservationId, String corridorId, String reviewId,
                            String routeId, long startMillis, long endMillis,
                            String status, String reservationKey,
                            long createdAt, Long cancelledAt) {
}
