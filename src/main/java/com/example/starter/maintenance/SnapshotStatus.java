package com.example.starter.maintenance;

/**
 * 冲突簇合并快照状态：MERGED 表示自动合并选出胜出版本，
 * PENDING_MANUAL 表示簇内观测全部受维护影响，不自动选胜出，等待人工裁决。
 */
public enum SnapshotStatus {
    /**
     * 自动合并完成：已从未受维护影响的观测中选出胜出版本。
     */
    MERGED,
    /**
     * 待人工裁决：冲突簇只含 MAINTENANCE_AFFECTED 观测，不自动选胜出。
     */
    PENDING_MANUAL
}
