package com.example.starter.race.domain;

/**
 * 名单成员状态：
 * ACTIVE-在队且计入未封榜的后续计分；
 * WITHDRAWN-已退赛，退赛只影响未封榜的后续计分，已锁定/已封榜成绩不重算。
 */
public enum RosterMemberState {
    ACTIVE,
    WITHDRAWN
}
