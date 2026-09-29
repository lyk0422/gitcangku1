package com.example.starter.batch;

/**
 * 合格证声明状态。
 * ACTIVE 已发布且可用于绑定与放行匹配；WITHDRAWN 已撤回，
 * 仅影响撤回之后的绑定与放行，历史放行固化快照不改写。
 */
public enum DeclarationStatus {
    ACTIVE,
    WITHDRAWN
}
