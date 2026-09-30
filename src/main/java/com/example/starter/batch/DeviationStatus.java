package com.example.starter.batch;

/**
 * 仓储温度偏差处置状态。
 * OPEN：未处置，批次门禁关闭，禁止放行、拆分和合批；
 * DISPOSED：两名不同角色（QUALITY/OPERATIONS）针对同一版本各确认一次后解除。
 */
public enum DeviationStatus {
    OPEN,
    DISPOSED
}
