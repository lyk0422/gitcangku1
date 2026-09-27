package com.example.starter.domain;

/**
 * 航线类型。
 */
public enum RouteType {

    /** 常规航线：受应急走廊限制，占用可被强制让渡。 */
    REGULAR,

    /** 应急航线：仍须通过禁飞审查，但不受应急走廊限制。 */
    EMERGENCY
}
