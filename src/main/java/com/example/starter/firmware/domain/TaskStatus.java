package com.example.starter.firmware.domain;

/**
 * 投放任务状态：PENDING 分片接收中；INSTALLABLE 完整性核验通过、可安装；
 * INTEGRITY_FAILED 完整性失败（禁止安装与成功回执，不计设备执行失败率）；
 * SUCCESS 安装成功；FAILED 安装失败；CANCELLED 已取消。
 */
public enum TaskStatus {
    PENDING,
    INSTALLABLE,
    INTEGRITY_FAILED,
    SUCCESS,
    FAILED,
    CANCELLED
}
