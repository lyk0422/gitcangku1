package com.example.starter.plan.model;

/**
 * 资格状态：ACTIVE 有效；SUPERSEDED 被同一乘务员的新版本授予取代；
 * REVOKED 已撤销。已发布计划的快照保留发布当时的版本，不随状态变化改写。
 */
public enum QualificationStatus {
    ACTIVE,
    SUPERSEDED,
    REVOKED
}