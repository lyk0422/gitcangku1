package com.example.starter.domain;

/** 空域占用类型。 */
public enum OccupancyKind {
    /** 常规占用：命中有效应急走廊时必须让渡；新建时命中有效走廊返回 409。 */
    REGULAR,
    /** 应急占用：允许使用有效走廊覆盖的路径段，但仍必须通过既有禁飞区审查。 */
    EMERGENCY
}
