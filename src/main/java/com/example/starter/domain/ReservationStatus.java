package com.example.starter.domain;

/**
 * 预占状态机：
 * RESERVED（预占中，占用两级额度）→ CONFIRMED（已确认，持续占用）／CANCELLED（已取消，已释放）；
 * RESERVED 到期 → EXPIRED（已过期，已释放）。
 */
public enum ReservationStatus {
    RESERVED,
    CONFIRMED,
    CANCELLED,
    EXPIRED
}
