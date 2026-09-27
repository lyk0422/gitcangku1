package com.example.starter.repo;

/**
 * 让渡航线显式重新申请不可变历史记录。
 *
 * @param seq            单调递增申请序号
 * @param corridorId     原让渡走廊标识
 * @param oldOccupancyId 原被让渡占用标识
 * @param newOccupancyId 重新申请新建占用标识
 * @param routeId        航线标识
 * @param reviewId       重新审查标识
 * @param result         处置结果：APPROVED
 * @param requestId      重新申请请求标识
 * @param appliedMs      申请提交时刻 epoch 毫秒 UTC
 */
public record ReapplyHistoryPo(long seq, String corridorId, String oldOccupancyId,
                               String newOccupancyId, String routeId, String reviewId,
                               String result, String requestId, long appliedMs) {
}
