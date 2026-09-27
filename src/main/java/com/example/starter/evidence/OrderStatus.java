package com.example.starter.evidence;

/**
 * 移交令版本状态。VALID 现行；REVOKED 已作废。
 * 版本即使为 VALID，超出 [validFrom, validTo) 生效区间同样视为失效。
 */
public enum OrderStatus {
    VALID,
    REVOKED
}
