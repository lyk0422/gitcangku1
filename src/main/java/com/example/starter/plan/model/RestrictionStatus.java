package com.example.starter.plan.model;

/**
 * 气象限速令状态：ACTIVE 生效参与发布/改签裁决；REVOKED 已撤销，
 * 仅保留历史，不再影响后续发布或改签，且不改写已生成的重排记录。
 */
public enum RestrictionStatus {
    ACTIVE,
    REVOKED
}
