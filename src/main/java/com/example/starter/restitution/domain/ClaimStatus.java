package com.example.starter.restitution.domain;

/**
 * 主张状态：REGISTERED=有效；WITHDRAWN=已撤回，不可恢复、内容不可改。
 */
public enum ClaimStatus {
    REGISTERED,
    WITHDRAWN
}
