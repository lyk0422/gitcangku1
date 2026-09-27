package com.example.starter.firmware.domain;

/**
 * 投放任务状态：PENDING 待接收分片或待回执；INSTALLABLE 完整性核验通过可安装；
 * INTEGRITY_FAILED 完整性失败（禁止安装与成功回执，不计入失败率）；SUCCESS 成功；FAILED 失败；CANCELLED 已取消。
 */
public enum TaskStatus {
    PENDING,
    INSTALLABLE,
    INTEGRITY_FAILED,
    SUCCESS,
    FAILED,
    CANCELLED
}
