package com.example.starter.observation;

/**
 * 采样许可状态。
 */
public enum PermitStatus {

    /**
     * 有效：许可已创建且未撤销，可命中采样，可被提前结束或撤销。
     */
    ACTIVE,

    /**
     * 已撤销（终态）：不再命中任何采样，其下未合并观测进入 PERMIT_REVIEW；不可恢复。
     */
    REVOKED
}
