package com.example.starter.plan.model;

/**
 * 限速令状态：ACTIVE 生效中；SUPERSEDED 已被修订产生的新版本取代；
 * REVOKED 已撤销。仅 ACTIVE 版本参与限速判定。
 */
public enum RestrictionStatus {
    ACTIVE,
    SUPERSEDED,
    REVOKED
}
