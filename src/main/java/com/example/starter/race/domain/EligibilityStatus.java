package com.example.starter.race.domain;

/**
 * 选手成绩资格状态：
 * ELIGIBLE-正常具备排名资格；
 * MEDICAL_HOLD-查询或封榜时仍有生效中的医疗暂停。
 */
public enum EligibilityStatus {
    ELIGIBLE,
    MEDICAL_HOLD
}
