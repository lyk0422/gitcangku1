package com.example.starter.plan.model;

/**
 * 接续关系状态：ACTIVE 生效中；CANCELLED 已取消（记录保留，不再参与判定）。
 */
public enum ConnectionStatus {
    ACTIVE,
    CANCELLED
}