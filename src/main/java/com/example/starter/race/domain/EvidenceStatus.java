package com.example.starter.race.domain;

/**
 * 冲线证据状态：PENDING-待裁决；ADJUDICATED-已裁决（不可撤回）；WITHDRAWN-已撤回（保留撤回记录）。
 */
public enum EvidenceStatus {
    PENDING,
    ADJUDICATED,
    WITHDRAWN
}
