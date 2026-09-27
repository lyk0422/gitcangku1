package com.example.starter.pallet;

/**
 * 托盘状态机。
 * OPEN 未装运，可装载、解除装载、变更隔板（无在托批次时）；
 * SHIPPED 已装运，装载关系与快照固化，仅可进入隔离处置；
 * QUARANTINE_DISPOSAL 已装运后发现类别错误进入隔离处置（终态），装载记录保留不删除。
 */
public enum PalletStatus {
    OPEN,
    SHIPPED,
    QUARANTINE_DISPOSAL
}
