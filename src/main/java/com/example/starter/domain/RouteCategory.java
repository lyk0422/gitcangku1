package com.example.starter.domain;

/** 航线类别：普通航线命中跑道关闭窗口即拒绝；紧急航线可按窗口例外标志附事件号通过。 */
public enum RouteCategory {
    /** 普通航线：起降时刻与任一关闭窗口相交即审查拒绝（422）。 */
    NORMAL,
    /** 紧急航线：仅在窗口允许紧急例外且附事件号时可通过。 */
    EMERGENCY
}
