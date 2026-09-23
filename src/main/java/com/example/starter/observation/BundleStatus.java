package com.example.starter.observation;

/**
 * 关联观测簇状态。
 */
public enum BundleStatus {
    /**
     * 未结：仍可登记冲突并发起联合裁决。
     */
    OPEN,
    /**
     * 已结：联合裁决成功后一次性关闭，不能再次裁决。
     */
    CLOSED
}
