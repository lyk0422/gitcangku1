package com.example.starter.race.domain;

/**
 * 选手成绩状态：
 * RANKED-正常参与排名；
 * UNTIMED-尚无完赛计时，不排名；
 * MISSING_CHECKPOINT-已有完赛计时但未覆盖全部检查点，不排名；
 * DISQUALIFIED-存在生效取消资格处罚，不排名；
 * WITHDRAWN-已退赛，不排名，不可开始或恢复医疗暂停；
 * MEDICAL_HOLD-存在生效中的医疗暂停，资格暂停，不排名，恢复后重新参与排名。
 */
public enum EntryStatus {
    RANKED,
    UNTIMED,
    MISSING_CHECKPOINT,
    DISQUALIFIED,
    WITHDRAWN,
    MEDICAL_HOLD
}
