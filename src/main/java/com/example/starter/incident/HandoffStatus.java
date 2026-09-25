package com.example.starter.incident;

/**
 * 互助交接状态机：ACTIVE → SETTLED。
 * 目标关闭或租约到期触发结束（记录 endReason/endTriggeredAt），
 * 全部资源项归还来源后进入 SETTLED 终态。
 */
public enum HandoffStatus {

    /** 进行中（含已触发结束但仍有已开始任务占用资源、等待结算）。 */
    ACTIVE,

    /** 已结算：全部资源项已归还来源，终态。 */
    SETTLED;
}
