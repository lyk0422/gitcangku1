package com.example.starter.bridge;

/**
 * 申请拒绝原因：OVERWEIGHT 超重；TIME_REGRESSION 时间倒退；WINDOW_OVERLAP 窗口重叠。
 */
public enum RejectReason {
    OVERWEIGHT,
    TIME_REGRESSION,
    WINDOW_OVERLAP
}