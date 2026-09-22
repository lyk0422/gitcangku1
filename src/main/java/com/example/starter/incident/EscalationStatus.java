package com.example.starter.incident;

/**
 * 遏制逾期升级记录状态。
 * OPEN：逾期未遏制，待当前指挥人确认；
 * ACKNOWLEDGED：当前指挥人已提交处置说明确认，永久保留；
 * CANCELLED：事件进入 CONTAINED 时仍 OPEN 的记录被原子取消。
 * 状态只能 OPEN → ACKNOWLEDGED 或 OPEN → CANCELLED，不允许回退。
 */
public enum EscalationStatus {

    /** 逾期未遏制，待当前指挥人确认。 */
    OPEN,

    /** 已被当前指挥人确认，记录确认人与确认 UTC 时刻。 */
    ACKNOWLEDGED,

    /** 事件进入 CONTAINED 时原子取消；已确认记录不会进入此状态。 */
    CANCELLED
}
