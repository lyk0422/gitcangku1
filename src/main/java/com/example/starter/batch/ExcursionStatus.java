package com.example.starter.batch;

/**
 * 仓储温度偏差处置状态：OPEN 未处置（批次门禁生效）；RESOLVED 两名不同角色确认同一限值版本后解除。
 */
public enum ExcursionStatus {
    OPEN,
    RESOLVED
}
