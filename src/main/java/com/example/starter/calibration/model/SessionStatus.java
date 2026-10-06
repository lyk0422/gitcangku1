package com.example.starter.calibration.model;

/**
 * 迟滞校准会话状态：OPEN=可追加，CLOSED=已关闭（冻结全部方向与精确结果，可整批放行）。
 */
public enum SessionStatus {
    OPEN,
    CLOSED
}
