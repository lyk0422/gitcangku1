package com.example.starter.calibration.model;

/**
 * 失效单状态。
 */
public enum InvalidationStatus {

    /** 待激活：已创建并保存闭包快照，等待两名不同质量人员确认。 */
    PENDING,

    /** 已激活：双人确认后整体冻结生效，生成唯一影响版本号。 */
    ACTIVATED
}
