package com.example.starter.domain;

/** 审核结论：命中任一有效禁飞区为 BLOCKED；否则连续失联超限为 LINK_BLOCKED；否则 CLEAR。 */
public enum ReviewConclusion {
    /** 航线与全部有效禁飞区均不相交，且通信覆盖达标（或无通信档案不适用）。 */
    CLEAR,
    /** 航线有点或线段与至少一个有效禁飞区相交（含边界接触）；优先于通信结论。 */
    BLOCKED,
    /** 未命中禁飞区，但存在严格超过 5000 毫秒的连续失联（等于 5000 允许）。 */
    LINK_BLOCKED
}
