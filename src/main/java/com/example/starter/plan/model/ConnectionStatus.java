package com.example.starter.plan.model;

/**
 * 接续登记状态：ACTIVE 生效（参与风险重算与保障改签）；
 * CANCELLED 已取消（历史行保留，不再参与任何裁决，且不可恢复）。
 */
public enum ConnectionStatus {
    ACTIVE,
    CANCELLED
}
