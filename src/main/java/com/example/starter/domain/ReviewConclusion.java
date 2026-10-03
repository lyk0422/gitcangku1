package com.example.starter.domain;

/** 审核结论：命中任一有效禁飞区为 BLOCKED，任一预测气象值低于区域最低标准为 WEATHER_BLOCKED，否则 CLEAR。 */
public enum ReviewConclusion {
    /** 航线与全部有效禁飞区均不相交，且经过的气象区域预测值均不低于最低标准。 */
    CLEAR,
    /** 航线有点或线段与至少一个有效禁飞区相交（含边界接触）。 */
    BLOCKED,
    /** 航线经过的气象区域中，至少一个预测能见度或云底低于该时段最低标准。 */
    WEATHER_BLOCKED
}