package com.example.starter.incident;

/**
 * 演练批次状态：ACTIVE 可写入，CLEANED 已清理（墓碑，批次键不可复用）。
 */
public enum DrillBatchStatus {

    /** 活跃批次，可继续创建该批次演练事件。 */
    ACTIVE,

    /** 已被原子清理；保留墓碑防止批次标识复用。 */
    CLEANED
}
