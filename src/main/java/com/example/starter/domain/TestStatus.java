package com.example.starter.domain;

/**
 * 检测记录状态：VALID 有效；VOID 已作废（取样记录作废，历史数据保留不删改）。
 */
public enum TestStatus {
    VALID,
    VOID
}
