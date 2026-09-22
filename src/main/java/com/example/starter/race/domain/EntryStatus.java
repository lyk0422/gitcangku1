package com.example.starter.race.domain;

/**
 * 选手成绩状态：RANKED-正常参与排名；UNTIMED-计时缺失不排名；
 * DISQUALIFIED-存在生效取消资格处罚不排名；
 * MISSING_CHECKPOINT-已有完赛计时但未覆盖全部检查点，不参与排名（覆盖后恢复原有排名规则）。
 */
public enum EntryStatus {
    RANKED,
    UNTIMED,
    DISQUALIFIED,
    MISSING_CHECKPOINT
}
