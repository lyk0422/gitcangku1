package com.example.starter.race.domain;

/**
 * 冲线证据状态：
 * PENDING-已登记、尚未裁决，可撤回；
 * ADJUDICATED-已裁决，关联不可变裁决快照，不可撤回、不可重复裁决；
 * REVOKED-未裁决证据被撤回，保留撤回记录，不可再裁决。
 */
public enum EvidenceStatus {
    PENDING,
    ADJUDICATED,
    REVOKED
}
