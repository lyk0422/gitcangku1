package com.example.starter.repo;

/**
 * 走廊不可变事件记录（建立/撤销）。
 *
 * @param seq        单调递增事件序号
 * @param corridorId 走廊标识
 * @param version    事件对应走廊版本
 * @param action     CREATED / REVOKED
 * @param reason     动作原因；null 表示未提供
 * @param requestId  触发事件的请求标识
 * @param eventMs    事件记录时刻 epoch 毫秒 UTC
 * @param xMin       事件时走廊矩形左边界（含），米
 * @param yMin       下边界（含），米
 * @param xMax       右边界（含），米
 * @param yMax       上边界（含），米
 * @param startMs    有效期起点（含），epoch 毫秒 UTC
 * @param endMs      有效期终点（不含），epoch 毫秒 UTC
 * @param altMin     高度下界（含），米
 * @param altMax     高度上界（不含），米
 * @param priority   走廊优先级
 */
public record CorridorEventPo(long seq, String corridorId, int version, String action,
                              String reason, String requestId, long eventMs,
                              int xMin, int yMin, int xMax, int yMax,
                              long startMs, long endMs, int altMin, int altMax, int priority) {
}
