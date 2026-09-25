package com.example.starter.firmware.domain;

/**
 * 隔离操作类型：QUARANTINE 运维提交隔离；RELEASE 不同运维确认解除。
 */
public enum QuarantineAction {
    QUARANTINE,
    RELEASE
}
