package com.example.starter.evidence;

/**
 * 组合包逐件借出明细状态。
 * OUT 未归还；RETURNED 已随某归还批次归还（不可再变，历史不可覆盖）。
 */
public enum PackageItemStatus {
    OUT,
    RETURNED
}
