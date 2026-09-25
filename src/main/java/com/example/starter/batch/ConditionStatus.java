package com.example.starter.batch;

/**
 * 条件放行记录状态。
 * ACTIVE 条件期内有效（批次 CONDITIONAL，期内可用）；
 * FULFILLED 全部子项核销完成（同一事务内批次转为 RELEASED）；
 * EXPIRED 到期时刻仍有未核销子项，批次已降级回 PENDING_RELEASE，记录保留不删除。
 */
public enum ConditionStatus {
    ACTIVE,
    FULFILLED,
    EXPIRED
}
