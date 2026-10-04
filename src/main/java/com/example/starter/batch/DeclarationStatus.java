package com.example.starter.batch;

/**
 * 合格证声明版本状态：ACTIVE 有效；WITHDRAWN 已撤回。
 * 撤回只影响后续绑定与放行，不改写已固化的放行快照与历史。
 */
public enum DeclarationStatus {
    ACTIVE,
    WITHDRAWN
}