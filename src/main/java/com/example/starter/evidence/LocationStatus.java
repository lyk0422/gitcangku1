package com.example.starter.evidence;

/**
 * 库位状态。
 * ACTIVE 可用；DISABLED 已停用（不可再作为迁移目标，历史迁移不受影响）。
 */
public enum LocationStatus {
    ACTIVE,
    DISABLED
}
