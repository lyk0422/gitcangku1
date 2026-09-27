package com.example.starter.blind;

/**
 * 席位状态；封存席位永不再分配，且同一席位不能同时被分配与封存。
 */
public enum SeatStatus {
    /** 可分配。 */
    ACTIVE,
    /** 已随处理代码退役封存，不再参与分配与扩容配额。 */
    SEALED
}
