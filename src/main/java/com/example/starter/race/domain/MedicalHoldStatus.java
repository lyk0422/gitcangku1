package com.example.starter.race.domain;

/**
 * 医疗暂停状态：
 * ACTIVE-生效中（同一选手同时仅一条），期间提交的分段标为被排除计时；
 * RESUMED-已由不同医疗角色确认适赛，暂停结束后的新计时重新参与排名。
 */
public enum MedicalHoldStatus {
    ACTIVE,
    RESUMED
}
