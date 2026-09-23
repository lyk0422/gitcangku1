package com.example.starter.evidence;

/**
 * 组合借出包状态。
 * PARTIAL 尚有证物未归还（建包即为此状态，允许分批归还）；
 * CLOSED 全部证物已归还（最后一件归还时在同一事务自动关闭，不可单独调用关闭，不可再变）。
 */
public enum PackageStatus {
    PARTIAL,
    CLOSED
}
