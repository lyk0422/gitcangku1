package com.example.starter.race.domain;

/**
 * 选手在单个阶段内的资格状态：
 * COMPLETE-阶段完整（覆盖全部检查点）且原始用时在关门线 11F/10 内（等于允许）；
 * OUT-阶段完整但原始用时严格大于关门线；
 * INCOMPLETE-阶段缺点（未覆盖全部检查点）。
 */
public enum StageStatus {
    COMPLETE,
    OUT,
    INCOMPLETE
}
