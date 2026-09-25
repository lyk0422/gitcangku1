package com.example.starter.race.domain;

/**
 * 队伍名单锁定状态。
 */
public enum TeamLockStatus {
    /** 名单已锁定，禁止普通增删成员。 */
    LOCKED,
    /** 名单未锁定，可维护成员；锁定被裁判解除后也回到该状态。 */
    UNLOCKED
}
