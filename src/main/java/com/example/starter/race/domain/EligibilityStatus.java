package com.example.starter.race.domain;

/**
 * 选手资格状态（封榜快照固化，之后不可追溯改变）：
 * ELIGIBLE-适赛，可按成绩规则参与排名；
 * WITHDRAWN-已退赛，不参与排名；
 * MEDICAL_HOLD-医疗暂停生效中（未恢复），不参与临时排名。
 */
public enum EligibilityStatus {
    ELIGIBLE,
    WITHDRAWN,
    MEDICAL_HOLD
}
