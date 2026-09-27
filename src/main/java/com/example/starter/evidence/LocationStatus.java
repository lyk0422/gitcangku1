package com.example.starter.evidence;

/**
 * 库位状态。
 * ACTIVE 启用（可作为迁移目标，可上架证物）；DISABLED 已停用（不可再迁入，历史库存与迁移记录不变）。
 */
public enum LocationStatus {
    ACTIVE,
    DISABLED
}
