package com.example.starter.observation;

/**
 * 冲突簇合并状态。
 */
public enum ClusterMergeState {
    /**
     * 自动胜出：簇内至少一条未受影响观测，已自动选择胜出者并固化为观测版本。
     */
    AUTO_MERGED,
    /**
     * 待人工裁决：冲突簇只含 MAINTENANCE_AFFECTED 观测，不自动选择胜出者，等待人工裁决。
     */
    PENDING_MANUAL,
    /**
     * 已人工裁决：裁决人写入理由后选择了胜出提交（可为受影响观测），已固化为观测版本。
     */
    RESOLVED
}
