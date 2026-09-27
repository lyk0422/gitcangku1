package com.example.starter.incident;

/**
 * 决策提案状态：OPEN 表决中；EFFECTIVE 到期前达法定人数已生效；
 * EXPIRED 到期后失效。仅允许 OPEN→EFFECTIVE 或 OPEN→EXPIRED，终态不可再变。
 */
public enum ProposalStatus {
    OPEN,
    EFFECTIVE,
    EXPIRED
}
