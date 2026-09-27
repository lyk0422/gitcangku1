package com.example.starter.firmware.domain;

/**
 * 投放任务状态：PENDING 待回执（登记了分片清单的发布单还需先收齐分片）；
 * INSTALLABLE 分片完整性核验通过、可安装；INTEGRITY_FAILED 分片完整性失败（本尝试代次终态，禁止安装）；
 * SUCCESS 成功；FAILED 失败；CANCELLED 已取消。
 */
public enum TaskStatus {
    PENDING,
    INSTALLABLE,
    INTEGRITY_FAILED,
    SUCCESS,
    FAILED,
    CANCELLED
}
