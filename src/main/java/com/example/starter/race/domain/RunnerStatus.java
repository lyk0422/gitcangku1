package com.example.starter.race.domain;

/**
 * 选手参赛状态：ACTIVE-在赛；WITHDRAWN-已退赛（不参与排名、不占组别容量，不可再重分）。
 */
public enum RunnerStatus {
    ACTIVE,
    WITHDRAWN
}
