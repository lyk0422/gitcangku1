package com.example.starter.domain;

/** 审核结论：命中任一有效禁飞区为 BLOCKED；几何通过但能量不足为 ENERGY_BLOCKED；否则 CLEAR。 */
public enum ReviewConclusion {
    /** 航线与全部有效禁飞区均不相交，且能量合格（或无能量档案）。 */
    CLEAR,
    /** 航线有点或线段与至少一个有效禁飞区相交（含边界接触）；优先于能量结论。 */
    BLOCKED,
    /** 几何审查通过，但存在航点能量不足（累计耗能 + 备降耗能 + 必留余量超出发能量）。 */
    ENERGY_BLOCKED
}
