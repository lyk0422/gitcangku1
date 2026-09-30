package com.example.starter.calibration.model;

/**
 * 批次事件类型。事件流水只追加，类型集合保持稳定。
 */
public enum BatchEventType {

    /** 修订创建（含首版与后续修订），封存目标值、允许偏差与创建角色。 */
    REVISION_CREATED,

    /** 离群检测，封存目标值、阈值、读数、差额、超出量与提交角色。 */
    OUTLIER_DETECTED,

    /** 一次离群复核，封存复核人、结论、理由、引用版本与是否 STALE。 */
    REVIEW,

    /** 有效裁决形成，封存裁决结论、参与复核人及角色。 */
    VERDICT,

    /** 批次统计重算（REJECT 裁决后排除读数），封存实际/要求数量、均值等统计值。 */
    RECOMPUTE,

    /** 修订放行，封存放行时一致的读数、离群与复核集合快照。 */
    RELEASE
}
