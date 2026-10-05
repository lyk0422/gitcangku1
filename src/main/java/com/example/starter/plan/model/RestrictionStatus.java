package com.example.starter.plan.model;

/**
 * 限速令状态：ACTIVE 生效，参与发布与改签的限速判定；REVOKED 已撤销，
 * 不再影响后续发布或改签，但已生成的重排记录保持原样。
 */
public enum RestrictionStatus {
    ACTIVE,
    REVOKED
}
