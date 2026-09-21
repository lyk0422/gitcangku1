package com.example.starter.evidence;

/**
 * 交接记录状态。
 * PENDING 待接收；ACCEPTED 已接受；CANCELLED 已取消。决定后不可再变。
 */
public enum TransferStatus {
    PENDING,
    ACCEPTED,
    CANCELLED
}
