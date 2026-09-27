package com.example.starter.race.domain;

/**
 * 计时被排除（不参与排名）的原因。
 * MEDICAL_HOLD-在医疗暂停生效期间提交的分段计时；
 * 无排除时对应字段为 null。
 */
public enum TimingExclusionReason {
    MEDICAL_HOLD
}
