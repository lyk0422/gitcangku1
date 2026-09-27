package com.example.starter.domain;

/**
 * 审核结论。区域分为两类：未配置气象最低标准的区域为绝对禁飞区；
 * 配置了气象最低标准的区域为条件区域，预测达标时允许经过。
 * 结论优先级：BLOCKED &gt; WEATHER_BLOCKED &gt; CLEAR。
 */
public enum ReviewConclusion {
    /** 未命中绝对禁飞区，且经过的全部条件区域各时段预测均不低于标准。 */
    CLEAR,
    /** 航线有点或线段与至少一个未配置气象标准的有效禁飞区相交（含边界接触）。 */
    BLOCKED,
    /** 未命中绝对禁飞区，但经过的某个条件区域任一时段预测能见度或云底低于标准。 */
    WEATHER_BLOCKED
}
