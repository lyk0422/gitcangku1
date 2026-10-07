package com.example.starter.batch;

/**
 * 合格证声明版本状态。
 * ACTIVE 可参与绑定与放行匹配；WITHDRAWN 已撤回，只影响后续绑定与放行，不改写历史快照。
 */
public enum DeclarationStatus {
    ACTIVE,
    WITHDRAWN
}
