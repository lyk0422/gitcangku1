package com.example.starter.batch;

/**
 * 托盘装载记录状态。
 * LOADED 在托；UNLOADED 已解除（释放分隔位与占用，历史保留）；
 * SHIPPED 已装运（终态，随装运放行固化，不得删除）。
 */
public enum PalletLoadStatus {
    LOADED,
    UNLOADED,
    SHIPPED
}
