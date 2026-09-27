package com.example.starter.batch;

/**
 * 托盘状态机。
 * OPEN 未装运（可装载、解除、修改隔板、装运放行）；
 * SHIPPED 已装运（装载记录固化为快照，仅可进入隔离处置）；
 * DISPOSAL 隔离处置（终态，已装运托盘发现类别错误后进入，装载记录保留不删除）。
 */
public enum PalletStatus {
    OPEN,
    SHIPPED,
    DISPOSAL
}
