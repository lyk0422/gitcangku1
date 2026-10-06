package com.example.starter.firmware.domain;

/**
 * 发布类型：NORMAL 普通灰度发布（任务终态 SUCCESS/FAILED）；
 * DUAL_PARTITION 双分区试启动发布（任务经 PENDING/TRIAL/COMMITTED/ROLLED_BACK 流转）。
 */
public enum ReleaseKind {
    NORMAL,
    DUAL_PARTITION
}
