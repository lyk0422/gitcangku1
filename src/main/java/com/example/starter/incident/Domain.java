package com.example.starter.incident;

/**
 * 事件域：REAL 真实事件域，DRILL 演练沙盘域。
 * 两域的事件、任务、依赖、统计与幂等键空间完全隔离，互不可见、不可跨域引用。
 */
public enum Domain {

    /** 真实事件域：默认域，查询不带显式演练标记时只返回本域。 */
    REAL,

    /** 演练沙盘域：事件创建时携带 drillKey 方进入本域。 */
    DRILL;

    /**
     * 按名称解析域；非法值返回 400 由调用方处理，这里统一抛出 IllegalArgumentException。
     */
    public static Domain fromParam(String value) {
        if (value == null || value.isBlank()) {
            return REAL;
        }
        return Domain.valueOf(value.strip().toUpperCase());
    }
}
