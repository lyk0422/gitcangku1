package com.example.starter.race.domain;

/**
 * 参赛状态：ACTIVE-在赛（未完赛，可提交读数、可更换芯片）；
 * WITHDRAWN-已退赛（只读，不得再提交读数或更换芯片）。
 *
 * <p>与 {@link EntryStatus} 区分：本枚举描述选手能否继续操作，
 * 退赛者在成绩榜单中的状态为 {@link EntryStatus#WITHDRAWN}。
 */
public enum RunnerStatus {
    ACTIVE,
    WITHDRAWN
}
