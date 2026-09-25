package com.example.starter.race.domain;

/**
 * 器材检录结果：
 * PASS-检录通过，有效至 inspectedAt + 赛事配置的有效分钟数，期间允许起跑；
 * FAIL-检录不通过，立即阻断起跑，直至该选手提交更晚的复检 PASS。
 */
public enum InspectionResult {
    PASS,
    FAIL
}
