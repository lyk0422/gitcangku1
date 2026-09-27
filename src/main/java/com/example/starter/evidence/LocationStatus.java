package com.example.starter.evidence;

/**
 * 库位状态。
 * ACTIVE 可用；DISABLED 已停用（不可作为迁移目标或入库位目标，历史库存与迁移记录不改写）。
 */
public enum LocationStatus {
    ACTIVE,
    DISABLED
}
