package com.example.starter.race.domain;

/**
 * 医疗暂停状态：
 * ACTIVE-生效中（同一选手同一时刻仅一条），该期间提交的分段计时标记为 MEDICAL_HOLD 不参与排名；
 * RESUMED-已由不同医疗角色确认适赛并恢复，恢复后仅新提交的分段计时参与排名。
 */
public enum MedicalHoldStatus {
    ACTIVE,
    RESUMED
}
