package com.example.starter.calibration.model;

/**
 * 测量批次状态：OPEN 开放（可提交读数、可放行），RELEASED 当前修订已放行。
 */
public enum BatchStatus {
    OPEN,
    RELEASED
}
