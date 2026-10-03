package com.example.starter.plan.model;

/**
 * 接续关系状态：ACTIVE 生效中参与保障重算；CANCELLED 已取消，行保留不删除，
 * 取消后不再参与风险判定，历史事件不受影响。
 */
public enum ConnectionStatus {
    ACTIVE,
    CANCELLED
}