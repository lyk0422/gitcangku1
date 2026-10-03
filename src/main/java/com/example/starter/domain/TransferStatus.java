package com.example.starter.domain;

/**
 * 交接单状态：PENDING 待确认 / CONFIRMED 已确认 / CANCELLED 已取消。
 */
public enum TransferStatus {
    /** 已发起、等待接收站点确认，持有权仍在发起站点。 */
    PENDING,
    /** 接收站点已确认，持有权已转移。 */
    CONFIRMED,
    /** 发起站点已取消，持有权保持原站点。 */
    CANCELLED
}