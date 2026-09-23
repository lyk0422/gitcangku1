package com.example.starter.observation;

/**
 * 观测记录归并状态。
 */
public enum RecordStatus {
    /**
     * 活跃、未归并：可参与离线合并、冲突解决、删除，也可作为重复观测簇成员提交归并。
     */
    ACTIVE,
    /**
     * 已归并：原记录仅保留历史可查，拒绝后续离线更新、冲突解决与恢复（重新激活）。
     */
    MERGED
}
