package com.example.starter.race.domain;

/**
 * 赛事类型：STANDARD-标准赛事（固定检查点，原有规则）；
 * LAP-绕圈赛事（每圈按 A、B、FINISH 依次经过，按圈次完整性核验）。
 */
public enum RaceType {
    STANDARD,
    LAP
}