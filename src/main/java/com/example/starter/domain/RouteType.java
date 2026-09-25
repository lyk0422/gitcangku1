package com.example.starter.domain;

/** 航线类型：NORMAL 与关闭窗口相交即拒绝；EMERGENCY 仅在窗口允许例外且附事件号时可通过。 */
public enum RouteType {
    /** 普通航线。 */
    NORMAL,
    /** 紧急航线，可在窗口允许例外且附事件号时通过关闭窗口。 */
    EMERGENCY
}
