package com.example.starter.plan.model;

/**
 * 限速令状态：ACTIVE 生效中，参与发布/改签时的限速裁决；
 * REVOKED 已撤销，不再影响后续发布或改签，但已生成的重排记录不回写。
 */
public enum RestrictionStatus {
    ACTIVE,
    REVOKED
}