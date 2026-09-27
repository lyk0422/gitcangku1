package com.example.starter.race.domain;

/**
 * 选手成绩状态：
 * RANKED-正常参与排名；
 * UNTIMED-尚无完赛计时，不排名；
 * MISSING_CHECKPOINT-已有完赛计时但有效分段未覆盖全部检查点，不排名；
 * WITHDRAWN-已退赛，不排名；
 * DISQUALIFIED-存在生效取消资格处罚，不排名。
 *
 * <p>医疗暂停本身不是成绩状态：暂停是资格区间，暂停区间内提交的分段计时保存但标记
 * MEDICAL_HOLD 并从排名与覆盖统计中排除；暂停中选手若无其他排除情形，其暂停前计时仍有效。
 */
public enum EntryStatus {
    RANKED,
    UNTIMED,
    MISSING_CHECKPOINT,
    WITHDRAWN,
    DISQUALIFIED
}
