package com.example.starter.certificate;

/**
 * 合格证声明版本状态。
 * ACTIVE 有效，可绑定、可放行；WITHDRAWN 已撤回，只影响后续绑定与放行，不改写历史。
 */
public enum DeclarationStatus {
    ACTIVE,
    WITHDRAWN
}