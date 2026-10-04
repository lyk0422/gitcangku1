package com.example.starter.incident;

/**
 * 交接简报状态：CURRENT 当前有效（同一交接单至多一条），
 * SUPERSEDED 已被刷新取代（保留供查阅，回读与接收均不可再用），
 * FROZEN 交接成功时与回读人一起冻结，作为交接证据永久保留。
 */
public enum BriefingStatus {

    /** 当前有效简报，可被回读、被接收引用或被刷新取代。 */
    CURRENT,

    /** 已被新版本简报取代；旧回读不能复用。 */
    SUPERSEDED,

    /** 交接完成时冻结；与回读确认人一起作为交接证据。 */
    FROZEN
}