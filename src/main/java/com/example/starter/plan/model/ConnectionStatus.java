package com.example.starter.plan.model;

/**
 * 接续关系状态：ACTIVE 生效中，参与风险判定与保障改签；CANCELLED 已取消，
 * 记录保留不改写，仅作历史查询。
 */
public enum ConnectionStatus {
    ACTIVE,
    CANCELLED
}
