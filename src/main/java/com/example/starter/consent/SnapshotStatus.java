package com.example.starter.consent;

/**
 * 快照用途新鲜度：CURRENT 表示该用途当前有效代次与快照代次一致；
 * STALE 表示当前有效代次已变化（撤回或重新授权），快照内容保持不变。
 */
public enum SnapshotStatus {
    CURRENT,
    STALE
}
