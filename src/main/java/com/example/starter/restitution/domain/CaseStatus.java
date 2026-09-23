package com.example.starter.restitution.domain;

/**
 * 案件状态：OPEN=审理中可写入；DECIDED=已裁决终态，拒绝新写入。
 */
public enum CaseStatus {
    OPEN,
    DECIDED
}
