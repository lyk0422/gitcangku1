package com.example.starter.curtailment.commitment;

/**
 * 容量承诺状态：ACTIVE=有效可匹配发布，SUSPENDED=已暂停不再接受新发布。
 */
public enum CommitmentStatus {
    ACTIVE,
    SUSPENDED
}
