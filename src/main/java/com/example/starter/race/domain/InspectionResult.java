package com.example.starter.race.domain;

/**
 * 器材检录结果：
 * PASS-通过，有效至检录时刻加赛事配置的有效分钟数（含端点）；
 * FAIL-不通过，立即阻断起跑，直至被后续复检PASS替换。
 */
public enum InspectionResult {
    PASS,
    FAIL
}
