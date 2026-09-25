package com.example.starter.race.domain;

/**
 * 选手起跑/退赛状态：
 * REGISTERED-已登记未起跑；
 * STARTED-已起跑（显式起跑或首个分段计时被接受时置位）；
 * WITHDRAWN-已退赛，终态。
 * 完赛与取消资格分别由 runner.finish_time_ms 与生效 DISQUALIFY 处罚表达，不在本枚举内。
 */
public enum RunnerRaceState {
    REGISTERED,
    STARTED,
    WITHDRAWN
}
