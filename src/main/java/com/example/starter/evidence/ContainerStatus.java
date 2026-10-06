package com.example.starter.evidence;

/**
 * 封存箱状态。
 * SEALED 已封存；TRANSFER_PENDING 待接收（根箱发起交接后整树冻结）；
 * SEAL_BROKEN 封条异常（终态，不可恢复，使根箱不能交接）。
 */
public enum ContainerStatus {
    SEALED,
    TRANSFER_PENDING,
    SEAL_BROKEN
}
