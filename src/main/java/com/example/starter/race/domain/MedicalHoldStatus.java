package com.example.starter.race.domain;

/**
 * 医疗暂停状态：
 * ACTIVE-已登记未恢复，生效中；
 * RESUMED-已由不同医疗角色确认适赛并结束。
 */
public enum MedicalHoldStatus {
    ACTIVE,
    RESUMED
}
