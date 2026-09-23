package com.example.starter.race.domain;

/**
 * 赛事状态：OPEN-开放可写；SUSPENDED-中止暂停中，仅允许同 eventKey 恢复，禁止其他写入；
 * SEALED-已封榜，只读。
 */
public enum RaceStatus {
    OPEN,
    SUSPENDED,
    SEALED
}
