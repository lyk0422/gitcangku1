package com.example.starter.race.domain;

/**
 * 团队成绩状态：
 * COMPLETE-队中有至少3名 RANKED 选手，取总耗时最少的3人计入团队成绩；
 * INCOMPLETE-不足3名 RANKED 选手，不参与团队排名，名次与团队总耗时为 null。
 */
public enum TeamStatus {
    COMPLETE,
    INCOMPLETE
}
