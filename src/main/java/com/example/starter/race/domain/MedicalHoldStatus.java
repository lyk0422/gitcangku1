package com.example.starter.race.domain;

/**
 * 医疗暂停状态：
 * ACTIVE-已登记且生效中，该选手后续提交的分段计时标记为 MEDICAL_HOLD 排除；
 * RESUMED-已由不同医疗角色确认适赛并恢复，恢复后新提交的分段计时正常参与排名。
 */
public enum MedicalHoldStatus {
    ACTIVE,
    RESUMED
}
