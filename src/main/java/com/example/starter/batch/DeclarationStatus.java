package com.example.starter.batch;

/**
 * 合格证声明版本状态。
 * ACTIVE 有效，可绑定、可放行；WITHDRAWN 已撤回，撤回只影响后续绑定与放行，
 * 不改写已产生的绑定、放行快照与历史记录。
 */
public enum DeclarationStatus {
    ACTIVE,
    WITHDRAWN
}