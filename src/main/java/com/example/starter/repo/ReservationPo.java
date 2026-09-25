package com.example.starter.repo;

/**
 * 走廊预约记录（取消后行保留，状态置为 CANCELLED）。
 *
 * @param reservationKey 预约全局唯一业务键
 * @param corridorId     所属走廊标识
 * @param startTime      预约开始时刻，epoch 毫秒（UTC），含
 * @param endTime        预约结束时刻，epoch 毫秒（UTC），不含（左闭右开）
 * @param reviewId       关联的已 CLEAR 审核结果标识（仅引用，不消费不改写）
 * @param status         ACTIVE / CANCELLED
 * @param requestId      创建预约的写操作请求标识
 * @param createdAt      创建时间，epoch 毫秒（UTC）
 * @param cancelledAt    取消时间，epoch 毫秒（UTC）；NULL 表示未取消
 */
public record ReservationPo(String reservationKey, String corridorId, long startTime,
                            long endTime, String reviewId, String status, String requestId,
                            long createdAt, Long cancelledAt) {
}
