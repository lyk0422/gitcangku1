package com.example.starter.batch;

/**
 * 合格证声明状态：PUBLISHED 已发布可用；WITHDRAWN 已撤回（终态，只影响后续绑定与放行）。
 */
public enum DeclarationStatus {
    PUBLISHED,
    WITHDRAWN
}
