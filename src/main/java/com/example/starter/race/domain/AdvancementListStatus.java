package com.example.starter.race.domain;

/**
 * 晋级名单状态：ACTIVE-生效中（同一赛事最多一份），REVOKED-已整份撤销（不可变快照仍保留）。
 */
public enum AdvancementListStatus {
    ACTIVE,
    REVOKED
}
