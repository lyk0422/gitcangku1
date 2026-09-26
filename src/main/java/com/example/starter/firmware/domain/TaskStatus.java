package com.example.starter.firmware.domain;

/**
 * 投放任务状态：PENDING 待分片接收/待回执；INSTALLABLE 分片完整性核验通过、可安装；
 * SUCCESS 成功；FAILED 失败；INTEGRITY_FAILED 分片完整性失败（可重新拉取开启新尝试代次）；
 * CANCELLED 已取消。
 */
public enum TaskStatus {
    PENDING,
    INSTALLABLE,
    SUCCESS,
    FAILED,
    INTEGRITY_FAILED,
    CANCELLED
}
