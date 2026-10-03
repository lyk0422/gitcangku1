package com.example.starter.plan.model;

/**
 * 气象限速令状态：ACTIVE 生效，参与发布与改签的限速裁决；REVOKED 已撤销，
 * 仅保留历史，不再影响后续发布或改签，已生成的重排记录不回改。
 */
public enum RestrictionStatus {
    ACTIVE,
    REVOKED
}