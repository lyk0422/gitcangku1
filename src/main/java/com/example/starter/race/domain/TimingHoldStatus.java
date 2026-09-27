package com.example.starter.race.domain;

/**
 * 分段计时资格：
 * COUNTED-正常参与排名；
 * MEDICAL_HOLD-医疗暂停生效期间提交，永久不参与排名（写入时裁定，之后不可改写）。
 */
public enum TimingHoldStatus {
    COUNTED,
    MEDICAL_HOLD
}
