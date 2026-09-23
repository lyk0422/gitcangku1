package com.example.starter.observation;

/**
 * 簇内字段冲突状态。
 */
public enum BundleConflictStatus {
    /**
     * 未解决：等待联合裁决覆盖。
     */
    OPEN,
    /**
     * 已解决：联合裁决成功后随簇一并关闭。
     */
    RESOLVED
}
