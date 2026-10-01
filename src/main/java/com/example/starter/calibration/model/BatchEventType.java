package com.example.starter.calibration.model;

/**
 * 批次事件类型。事件不可变追加，按事件 ID 升序即提交顺序。
 */
public enum BatchEventType {

    /** 离群检测：读数计算值超出目标值±允许偏差，封存目标值、阈值与读数。 */
    OUTLIER_DETECTED,

    /** 复核记录：每次复核（含 STALE）追加一条，封存复核角色与结论。 */
    REVIEW_RECORDED,

    /** 裁决形成：两名复核角色结论一致，封存最终结论。 */
    VERDICT_REACHED,

    /** 统计重算：REJECT 裁决排除读数后在同一事务内重算批次统计。 */
    STATS_RECOMPUTED,

    /** 修订创建：已放行批次创建后续测量修订版本。 */
    REVISION_CREATED,

    /** 批次放行：批次放行并生成不可改写的放行快照。 */
    BATCH_RELEASED
}