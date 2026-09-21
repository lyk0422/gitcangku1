package com.example.starter.evidence.domain;

/**
 * 交接记录状态：PENDING 待接收；ACCEPTED 已接收；CANCELLED 已取消。记录只追加，状态从 PENDING 单向流转。
 */
public enum TransferStatus {
    PENDING,
    ACCEPTED,
    CANCELLED
}
