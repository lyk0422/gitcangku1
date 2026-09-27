package com.example.starter.domain;

/** 走廊不可变事件动作：建立与撤销。 */
public enum CorridorEventAction {
    /** 走廊建立（版本 1）。 */
    CREATED,
    /** 走廊撤销（沿用走廊版本，记录撤销时刻与原因）。 */
    REVOKED
}
