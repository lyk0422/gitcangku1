package com.example.starter.domain;

/**
 * 交接单状态。
 * PENDING：待确认，可取消；CONFIRMED：已确认，持有权已转移，不得取消；CANCELLED：已取消。
 */
public enum TransferStatus {
    PENDING,
    CONFIRMED,
    CANCELLED
}