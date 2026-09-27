package com.example.starter.domain;

/** 审核结论：命中任一有效禁飞区为 BLOCKED，预测气象低于区域最低标准为 WEATHER_BLOCKED，否则 CLEAR。 */
public enum ReviewConclusion {
    /** 航线与全部有效禁飞区均不相交，且气象检查通过（或未做气象检查）。 */
    CLEAR,
    /** 航线有点或线段与至少一个有效禁飞区相交（含边界接触）。 */
    BLOCKED,
    /** 路径经过区域的预测能见度或云底低于该区域生效时段的最低标准。 */
    WEATHER_BLOCKED
}
