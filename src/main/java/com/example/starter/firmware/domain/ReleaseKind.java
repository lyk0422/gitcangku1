package com.example.starter.firmware.domain;

/**
 * 发布类型：GRAY 普通灰度发布（任务终态 SUCCESS/FAILED）；
 * DUAL_PARTITION 双分区试启动发布（任务经历 PENDING、TRIAL、COMMITTED、ROLLED_BACK）。
 */
public enum ReleaseKind {
    GRAY,
    DUAL_PARTITION
}