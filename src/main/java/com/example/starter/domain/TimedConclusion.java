package com.example.starter.domain;

/** 定时航线审查结论：速度与禁飞检查全部通过为 FEASIBLE，命中禁飞区为 BLOCKED。 */
public enum TimedConclusion {
    /** 各航段速度均在 [1,50] 米/秒且未命中任何有效禁飞区，可按计划时刻飞行。 */
    FEASIBLE,
    /** 速度可行，但航线与至少一个有效禁飞区相交（含边界接触）。 */
    BLOCKED
}