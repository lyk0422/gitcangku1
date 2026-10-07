package com.example.starter.domain;

/** 审核结论：命中任一有效禁飞区为 BLOCKED，否则按链路覆盖判定，均不满足为 CLEAR。 */
public enum ReviewConclusion {
    /** 航线与全部有效禁飞区均不相交，且链路覆盖达标（或无通信档案）。 */
    CLEAR,
    /** 航线有点或线段与至少一个有效禁飞区相交（含边界接触）；优先于链路结论。 */
    BLOCKED,
    /** 未命中禁飞区，但存在严格超过 5000 毫秒的连续失联（等于 5000 允许）。 */
    LINK_BLOCKED
}
