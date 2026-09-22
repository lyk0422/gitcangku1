package com.example.starter.incident;

/**
 * 升级记录状态：OPEN 待确认 / ACKNOWLEDGED 已确认 / CANCELLED 已取消。
 * OPEN 仅能由当前指挥人确认进入 ACKNOWLEDGED，或在事件进入 CONTAINED 时原子置为 CANCELLED；
 * 已确认记录保留，不允许回退。
 */
public enum EscalationStatus {

    /** 逾期未遏制，等待当前指挥人确认。 */
    OPEN,

    /** 当前指挥人已提交处置说明并确认。 */
    ACKNOWLEDGED,

    /** 事件进入 CONTAINED 时仍为 OPEN，被原子取消；已确认记录不受影响。 */
    CANCELLED
}
