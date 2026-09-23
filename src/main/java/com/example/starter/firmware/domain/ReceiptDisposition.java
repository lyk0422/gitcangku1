package com.example.starter.firmware.domain;

/**
 * 回执处置：SETTLED 按当前代次结算；LATE 迟到旧代次仅存档，不改变任何统计。
 */
public enum ReceiptDisposition {
    SETTLED,
    LATE
}
