package com.example.starter.firmware.domain;

/**
 * 版本冻结区间状态：FROZEN 冻结生效中（不得新建正式任务）；RESUMED 已由异于冻结人的审批人批准恢复（终态，区间保留）。
 */
public enum FreezeStatus {
    FROZEN,
    RESUMED
}
