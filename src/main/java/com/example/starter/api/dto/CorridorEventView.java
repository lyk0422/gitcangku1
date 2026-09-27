package com.example.starter.api.dto;

/**
 * 走廊不可变事件只读视图，按 seq 升序返回。
 *
 * @param seq       单调递增事件序号
 * @param corridorId 走廊标识
 * @param version   事件对应走廊版本
 * @param action    CREATED / REVOKED
 * @param reason    动作原因；null 表示未提供
 * @param requestId 触发事件的请求标识
 * @param eventMs   事件时刻，epoch 毫秒 UTC
 */
public record CorridorEventView(long seq, String corridorId, int version, String action,
                                String reason, String requestId, long eventMs) {
}
