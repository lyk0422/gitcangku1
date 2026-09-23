package com.example.starter.plan.model;

/**
 * 区段封锁切换单状态：REGISTERED 已登记、映射已提交待激活；
 * ACTIVE 已激活，旧计划全部 SUSPENDED、替代计划全部 PUBLISHED，此后不可变。
 */
public enum DisruptionStatus {
    REGISTERED,
    ACTIVE
}
