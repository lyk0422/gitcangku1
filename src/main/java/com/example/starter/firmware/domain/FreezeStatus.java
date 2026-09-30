package com.example.starter.firmware.domain;

/**
 * 版本冻结记录状态：FROZEN 冻结生效中（禁止为该版本新建正式任务）；
 * RESUMED 已由异于冻结创建人的操作人审批恢复（冻结区间保留，历史不可改）。
 */
public enum FreezeStatus {
    FROZEN,
    RESUMED
}
