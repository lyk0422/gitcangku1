package com.example.starter.evidence;

/**
 * 库位状态：ACTIVE 启用（可作为入库与迁移目标）；DISABLED 已停用（不可作为目标，确认时返回 409）。
 */
public enum LocationStatus {
    ACTIVE,
    DISABLED
}
