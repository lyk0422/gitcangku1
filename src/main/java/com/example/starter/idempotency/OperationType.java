package com.example.starter.idempotency;

/** 受幂等保护的写操作类型，记入去重表便于追踪。 */
public enum OperationType {
    CREATE_EXPERIMENT,
    ENROLL,
    WITHDRAW,
    CLOSE_EXPERIMENT,
    REQUEST_UNBLIND,
    APPROVE_UNBLIND
}
