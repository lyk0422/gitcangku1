package com.example.starter.race.domain;

/**
 * 选手成绩状态：
 * RANKED-正常参与排名；
 * UNTIMED-尚无完赛计时（绕圈赛事为尚无任何圈次记录），不排名；
 * MISSING_CHECKPOINT-已有完赛计时但未覆盖全部检查点，不排名；
 * LAP_INCOMPLETE-绕圈赛事已有部分圈次记录但存在漏圈或逆序，不排名且阻止整场封榜；
 * DISQUALIFIED-存在生效取消资格处罚，不排名。
 */
public enum EntryStatus {
    RANKED,
    UNTIMED,
    MISSING_CHECKPOINT,
    LAP_INCOMPLETE,
    DISQUALIFIED
}