package com.example.starter.race.domain;

/**
 * 申诉裁决结果（均为终态）：
 * ACCEPT-申诉成立，保证金全额退回；REJECT-申诉驳回，保证金全部没收。
 */
public enum ProtestDecision {
    ACCEPT,
    REJECT
}
