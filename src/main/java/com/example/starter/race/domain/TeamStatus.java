package com.example.starter.race.domain;

/**
 * 团队成绩状态：
 * COMPLETE-队内至少3名 RANKED 选手，已产生团队总耗时与名次；
 * INCOMPLETE-队内 RANKED 选手不足3人，名次与总耗时为 null。
 */
public enum TeamStatus {
    COMPLETE,
    INCOMPLETE
}
