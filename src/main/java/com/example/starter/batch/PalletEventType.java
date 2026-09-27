package com.example.starter.batch;

/**
 * 托盘事件类型，仅追加不改写：
 * LOADED 装载达成；UNLOADED 解除装载（释放分隔位，历史保留）；
 * DIVIDERS_CONFIGURED 隔板数量变更（产生新隔板版本）；
 * SHIPPED 装运放行（固化快照）；QUARANTINED 已装运托盘发现类别错误进入隔离处置；
 * REVIEW_FLAGGED 血缘召回命中同托盘批次，未装运批次被标记待复核。
 */
public enum PalletEventType {
    LOADED,
    UNLOADED,
    DIVIDERS_CONFIGURED,
    SHIPPED,
    QUARANTINED,
    REVIEW_FLAGGED
}
