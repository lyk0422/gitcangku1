package com.example.starter.observation;

/**
 * 置信度轨迹事件类型。
 */
public enum ConfidenceEventType {

    /** 观测创建：初始置信度 100。 */
    INITIAL,

    /** 复核确认：同版本同类别首个有效确认扣减 20，重复确认扣减 0。 */
    CONFIRMED,

    /** 复核驳回：不影响置信度，delta 为 0。 */
    DISMISSED,

    /** 新版本生成：固化该版本继承到的置信度（记录版本轨迹点，无数值变化）。 */
    VERSION
}
