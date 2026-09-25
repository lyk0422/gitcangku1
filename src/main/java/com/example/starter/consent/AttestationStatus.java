package com.example.starter.consent;

/**
 * 接收方证明版本状态：ACTIVE 生效；SUPERSEDED 已被续签新版本取代；REVOKED 已撤销。
 * 同一接收方＋用途＋代次任一时刻至多一条 ACTIVE。
 */
public enum AttestationStatus {
    ACTIVE,
    SUPERSEDED,
    REVOKED
}
