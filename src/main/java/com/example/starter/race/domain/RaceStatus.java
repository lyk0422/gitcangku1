package com.example.starter.race.domain;

/**
 * 赛事状态：OPEN-开放可写；SUSPENDED-已中止，仅允许恢复；SEALED-已封榜，只读。
 */
public enum RaceStatus {
    OPEN,
    SUSPENDED,
    SEALED
}
