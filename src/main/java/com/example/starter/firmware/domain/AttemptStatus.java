package com.example.starter.firmware.domain;

/**
 * 尝试代次状态：RECEIVING 接收中；INTEGRITY_FAILED 完整性失败（终态，仅可重新拉取开新代次）；
 * INSTALLABLE 分片完整可安装（终态，等待安装回执）；CANCELLED 任务撤销（终态）。
 */
public enum AttemptStatus {
    RECEIVING,
    INTEGRITY_FAILED,
    INSTALLABLE,
    CANCELLED
}
