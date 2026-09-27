package com.example.starter.plan.model;

/**
 * 限速令状态：ACTIVE 生效中；REVOKED 已撤销（仅影响后续发布或改签，不改写历史重排记录）。
 */
public enum RestrictionStatus {
    ACTIVE,
    REVOKED
}
