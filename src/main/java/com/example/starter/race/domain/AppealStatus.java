package com.example.starter.race.domain;

/**
 * 申诉状态。
 * PENDING-已受理待裁决；UPHELD-裁决维持处罚；REMOVED-裁决撤销处罚；REPLACED-裁决替换罚时。
 */
public enum AppealStatus {
    PENDING,
    UPHELD,
    REMOVED,
    REPLACED
}
