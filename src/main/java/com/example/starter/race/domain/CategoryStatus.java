package com.example.starter.race.domain;

/**
 * 组别状态：
 * OPEN-开放，可迁入迁出与接收新申请；
 * SEALED-已封榜冻结，封榜快照仅固化封榜时刻所属该组别的在赛选手，后续不追溯重写。
 */
public enum CategoryStatus {
    OPEN,
    SEALED
}
