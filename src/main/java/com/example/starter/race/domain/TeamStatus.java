package com.example.starter.race.domain;

/**
 * 队伍名单状态：OPEN-可增删成员，LOCKED-已锁定禁止普通增删（可由裁判解锁回到OPEN）。
 */
public enum TeamStatus {
    OPEN,
    LOCKED
}
