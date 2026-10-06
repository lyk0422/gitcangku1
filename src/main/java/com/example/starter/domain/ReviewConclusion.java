package com.example.starter.domain;

/** 审核结论：能量不足为 ENERGY_BLOCKED，否则命中任一有效禁飞区为 BLOCKED，否则 CLEAR。 */
public enum ReviewConclusion {
    /** 航线与全部有效禁飞区均不相交，且能量审查合格（或无能量档案）。 */
    CLEAR,
    /** 能量审查合格（或无能量档案），但航线有点或线段与至少一个有效禁飞区相交（含边界接触）。 */
    BLOCKED,
    /** 能量审查不合格：至少一个航点 累计主航线耗能+备降耗能+必留余量 超出发可用能量；
     * 几何命中仍随结果保留，能量合格不会覆盖几何 BLOCKED。 */
    ENERGY_BLOCKED
}
