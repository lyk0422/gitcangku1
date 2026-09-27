package com.example.starter.domain;

/**
 * 应急走廊状态。自然到期不改变状态，由时间窗与当前时刻判定是否仍有效。
 */
public enum CorridorStatus {

    /** 有效（是否处于生效时刻还需结合时间窗判断）。 */
    ACTIVE,

    /** 已撤销，只影响后续占用，已让渡航线不自动恢复。 */
    REVOKED
}
