package com.example.starter.firmware.domain;

/**
 * 发布类型：NORMAL 普通灰度发布（回执 SUCCESS/FAILED）；
 * DUAL_BOOT 双分区试启动发布（试启动、健康确认、回滚，仅本题新建发布使用）。
 */
public enum ReleaseKind {
    NORMAL,
    DUAL_BOOT
}