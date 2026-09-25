package com.example.starter.incident;

/**
 * 隔离域：真实事件域与演练沙盘域互不相通。
 * 两域内 incidentKey、commandKey、统计与幂等键空间各自独立，
 * 任何写操作都不得跨域引用。
 */
public enum Domain {

    /** 真实事件域：查询默认域，升级会触发真实通知副作用。 */
    REAL,

    /** 演练沙盘域：查询须显式 includeDrill，升级不产生任何真实副作用，可按批次原子清理。 */
    DRILL;

    /**
     * 按外部标记解析域：drillKey 非空为演练域，否则真实域。
     */
    public static Domain ofDrillFlag(Boolean drill) {
        return Boolean.TRUE.equals(drill) ? DRILL : REAL;
    }
}
