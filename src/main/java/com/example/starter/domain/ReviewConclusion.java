package com.example.starter.domain;

/** 审核结论：命中任一有效禁飞区为 BLOCKED，否则 CLEAR。 */
public enum ReviewConclusion {
    /** 航线与全部有效禁飞区均不相交。 */
    CLEAR,
    /** 航线有点或线段与至少一个有效禁飞区相交（含边界接触）。 */
    BLOCKED
}
