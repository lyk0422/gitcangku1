package com.example.starter.domain;

/**
 * 占用种类：常规占用与应急占用。
 * 应急占用只能由命中有效应急走廊的应急航线提出，且仍须通过禁飞审查。
 */
public enum OccupancyKind {
    /** 常规航线占用：走廊创建时可被要求让渡，命中有效走廊的新建申请返回 409。 */
    REGULAR,
    /** 应急航线占用：必须命中有效应急走廊，且引用的审查为应急 CLEAR。 */
    EMERGENCY
}
