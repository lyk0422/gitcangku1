package com.example.starter.domain;

/** 应急走廊状态：只能创建（ACTIVE）或撤销（REVOKED）；是否当前有效另按时间窗判定。 */
public enum CorridorStatus {
    /** 走廊未撤销；在 [startUtc, endUtc) 时间窗内视为有效。 */
    ACTIVE,
    /** 走廊已撤销；撤销只影响后续占用，已让渡占用不自动恢复，记录保留不可变。 */
    REVOKED
}
