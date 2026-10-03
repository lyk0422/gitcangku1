package com.example.starter.domain;

/** 审核结论：命中任一有效禁飞区为 BLOCKED，预测气象低于区域标准为 WEATHER_BLOCKED，否则 CLEAR。 */
public enum ReviewConclusion {
    /** 航线与全部有效禁飞区均不相交，且（如提交气象版本）全部预测值不低于区域标准。 */
    CLEAR,
    /** 航线有点或线段与至少一个有效禁飞区相交（含边界接触）。 */
    BLOCKED,
    /** 未命中禁飞区，但路径经过区域的任一时段预测能见度或云底低于该区域最低标准。 */
    WEATHER_BLOCKED
}