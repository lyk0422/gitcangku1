package com.example.starter.race.domain;

/**
 * 选手在赛状态。
 */
public enum RunnerStatus {
    /** 在赛，参与排名与封榜。 */
    ACTIVE,
    /** 已退赛，不再参与排名与封榜；历史封榜快照不回溯修改。 */
    WITHDRAWN
}
