package com.example.starter.race.domain;

/**
 * 选手成绩状态：
 * RANKED-正常参与排名；
 * UNTIMED-尚无完赛计时，不排名；
 * MISSING_CHECKPOINT-已有完赛计时但未覆盖全部检查点，不排名；
 * DISQUALIFIED-存在生效取消资格处罚，不排名；
 * DNS-未出发（退赛登记），无任何分段记录与完赛计时，不排名且不占名次；
 * DNF-中途退赛（退赛登记），保留只读分段记录、无完赛计时，不排名且不占名次。
 */
public enum EntryStatus {
    RANKED,
    UNTIMED,
    MISSING_CHECKPOINT,
    DISQUALIFIED,
    DNS,
    DNF
}
