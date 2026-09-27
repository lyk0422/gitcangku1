package com.example.starter.domain;

/** 占用类型：常规占用命中有效走廊返回 409；应急占用可使用走廊，但仍须通过禁飞审查。 */
public enum OccupationKind {
    /** 常规航线占用。 */
    REGULAR,
    /** 应急航线占用，可使用有效应急走廊。 */
    EMERGENCY
}
