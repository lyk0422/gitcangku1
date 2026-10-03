package com.example.starter.race.domain;

/**
 * 选手状态：ACTIVE-在赛（可提交分段读数、可更换芯片）；
 * WITHDRAWN-已退赛（不再接收分段读数与芯片更换，成绩不参与排名）。
 */
public enum RunnerStatus {
    ACTIVE,
    WITHDRAWN
}
