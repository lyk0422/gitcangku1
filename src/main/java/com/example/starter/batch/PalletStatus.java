package com.example.starter.batch;

/**
 * 托盘状态机。
 * OPEN：未装运，可配置隔板、装载与解除装载；
 * SHIPPED：已装运放行，装载关系与隔板版本快照冻结，只能查询或因类别错误进入隔离处置；
 * QUARANTINED：已装运托盘事后发现类别错误时的隔离处置状态，装载记录保留，不得删除或解除。
 */
public enum PalletStatus {
    OPEN,
    SHIPPED,
    QUARANTINED
}
