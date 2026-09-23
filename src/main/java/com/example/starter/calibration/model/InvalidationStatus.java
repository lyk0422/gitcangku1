package com.example.starter.calibration.model;

/**
 * 失效单状态。
 */
public enum InvalidationStatus {

    /** 待双人确认：已创建并固化首次闭包快照，尚未激活。 */
    PENDING_CONFIRMATION,

    /** 已激活：闭包已在单事务内重算并整体冻结，生成唯一 impactVersion。 */
    ACTIVATED
}
