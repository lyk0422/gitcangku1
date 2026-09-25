package com.example.starter.domain;

/**
 * 走廊预约状态。
 *
 * <ul>
 *   <li>ACTIVE：生效预约，参与走廊容量计数；</li>
 *   <li>CANCELLED：已取消，立即从容量计数移除，历史记录保留。</li>
 * </ul>
 */
public enum ReservationStatus {
    ACTIVE,
    CANCELLED
}
