package com.example.starter.consent;

/**
 * 快照用途状态：CURRENT 表示该用途当前有效代次与快照一致；STALE 表示已撤回或已推进到新代次。
 * 状态仅用于读取时的标注，不回写快照内容。
 */
public enum SnapshotStatus {
    CURRENT,
    STALE
}
