package com.example.starter.evidence;

/**
 * 容器交接状态。
 * SEALED 可装箱/拆箱/发起交接；TRANSFER_PENDING 待接收（根箱发起交接后整个子树冻结，
 * 禁止成员变更，但允许原保管人检查成员封条）。
 */
public enum ContainerStatus {
    SEALED,
    TRANSFER_PENDING
}
