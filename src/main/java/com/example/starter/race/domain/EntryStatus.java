package com.example.starter.race.domain;

/**
 * 选手成绩状态：RANKED-正常参与排名；UNTIMED-计时缺失不排名；DISQUALIFIED-存在生效取消资格处罚不排名。
 */
public enum EntryStatus {
    RANKED,
    UNTIMED,
    DISQUALIFIED
}
