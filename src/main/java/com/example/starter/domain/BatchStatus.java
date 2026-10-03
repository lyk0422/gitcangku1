package com.example.starter.domain;

/**
 * 批次状态：OPEN=进行中（可入批、测量、交接），HANDED_OVER=已交接（冻结，禁止重新测量）。
 */
public enum BatchStatus {
    OPEN,
    HANDED_OVER
}