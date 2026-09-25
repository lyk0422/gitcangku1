package com.example.starter.incident;

/**
 * 资源状态：AVAILABLE 为来源事件自持可用；LEASED_OUT 为存在 ACTIVE 交接已跨事件借出。
 * 资源同一时刻至多受一条 ACTIVE 交接约束，故 LEASED_OUT 期间不可再次转借。
 */
public enum ResourceStatus {

    /** 来源事件自持，空闲可借。 */
    AVAILABLE,

    /** 已通过 ACTIVE 交接借出（含被目标事件已开始任务继续占用的超期时段）。 */
    LEASED_OUT;
}
