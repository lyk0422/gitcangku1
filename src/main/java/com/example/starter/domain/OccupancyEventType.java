package com.example.starter.domain;

/** 占用处置不可变事件类型。 */
public enum OccupancyEventType {
    /** 占用登记（常规或应急）。 */
    CREATED,
    /** 常规占用在走廊创建事务内被让渡。 */
    YIELDED,
    /** 已让渡原占用经重新审查后显式重新申请。 */
    REAPPLIED,
    /** 起飞登记。 */
    DEPARTED
}
