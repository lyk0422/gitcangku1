package com.example.starter.pallet;

/**
 * 托盘装载记录状态。
 * ACTIVE 在托（未装运）；UNLOADED 已解除装载（释放分隔位，记录保留）；
 * SHIPPED 已随托盘装运放行（快照固化，记录保留）。
 */
public enum LoadStatus {
    ACTIVE,
    UNLOADED,
    SHIPPED
}
