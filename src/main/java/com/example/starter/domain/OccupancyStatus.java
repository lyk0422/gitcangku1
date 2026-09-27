package com.example.starter.domain;

/**
 * 占用状态机。
 *
 * <p>ACTIVE（有效）→ YIELDED（被应急走廊让渡，不可自动恢复）；
 * ACTIVE → DEPARTED（已起飞，不可让渡）。YIELDED 航线只能重新审查并
 * 显式重新申请，新申请产生新的占用，原占用永久保留 YIELDED。</p>
 */
public enum OccupancyStatus {
    /** 有效占用：走廊创建时若时空相交，可让渡的被置为本态以外的 YIELDED。 */
    ACTIVE,
    /** 已让渡：走廊创建事务内固化，走廊撤销或到期均不自动恢复。 */
    YIELDED,
    /** 已起飞：走廊创建遇到该状态占用必须整次 422。 */
    DEPARTED
}
