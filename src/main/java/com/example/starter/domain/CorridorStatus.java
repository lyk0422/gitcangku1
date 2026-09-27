package com.example.starter.domain;

/**
 * 应急走廊状态：只能创建（ACTIVE）或撤销（REVOKED）。
 * 自然到期不写状态，有效性由 ACTIVE 状态与 UTC 时间窗共同判定。
 */
public enum CorridorStatus {
    /** 走廊已建立且未撤销；是否在有效期内另由时间窗判定。 */
    ACTIVE,
    /** 走廊已撤销，只影响后续占用，已让渡占用不恢复。 */
    REVOKED
}
