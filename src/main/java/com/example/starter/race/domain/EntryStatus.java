package com.example.starter.race.domain;

/**
 * 选手成绩状态：
 * RANKED-正常参与排名；
 * UNTIMED-尚无完赛计时（绕圈赛事为无任何圈次记录），不排名；
 * MISSING_CHECKPOINT-已有完赛计时但未覆盖全部检查点（标准赛事），不排名；
 * INCOMPLETE_LAPS-有部分圈次记录但漏圈/漏点或逆序（绕圈赛事），不排名且阻塞封榜；
 * DISQUALIFIED-存在生效取消资格处罚，不排名。
 */
public enum EntryStatus {
    RANKED,
    UNTIMED,
    MISSING_CHECKPOINT,
    INCOMPLETE_LAPS,
    DISQUALIFIED
}