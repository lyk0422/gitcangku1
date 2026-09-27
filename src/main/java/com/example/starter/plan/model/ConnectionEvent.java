package com.example.starter.plan.model;

/**
 * 接续业务证据（只插不改）：登记、风险判定、风险解除、保障改签决策与取消。
 * 所有时刻为 UTC 毫秒；可空字段以 null 表示不适用，语义稳定不改写。
 *
 * @param id                    主键，事件按 id 升序即发生顺序
 * @param eventType             事件类型：REGISTERED / RISK_MARKED / RISK_CLEARED / RESCHEDULED / CANCELLED
 * @param connectionId          所属接续 id
 * @param predecessorPlanId     事件发生时前车计划 id
 * @param successorPlanId       事件发生时后车计划 id
 * @param predecessorVersion    事件判定所依据的前车计划版本
 * @param predArrivalUtc        事件发生时前车终到时刻，UTC 毫秒
 * @param succDepartureUtc      事件发生时后车始发时刻（RESCHEDULED 为新时刻），UTC 毫秒
 * @param prevSuccDepartureUtc  改签前后车原始发时刻，UTC 毫秒；仅 RESCHEDULED 非空
 * @param guaranteeMinutes      接续要求保障分钟数
 * @param actualMinutes         实际接续分钟数 = 后车始发 - 前车终到，可为负
 * @param deficitMinutes        保障差额 = 要求 - 实际，正值不足、负值富余
 * @param windowStartUtc        可选改签时间窗起点（含），UTC 毫秒；仅 RISK_MARKED 非空
 * @param windowEndUtc          可选改签时间窗终点（不含），UTC 毫秒；仅 RISK_MARKED 非空
 * @param createdAt             事件记录时刻，UTC 毫秒
 */
public record ConnectionEvent(long id, String eventType, long connectionId,
                              long predecessorPlanId, long successorPlanId,
                              int predecessorVersion, long predArrivalUtc, long succDepartureUtc,
                              Long prevSuccDepartureUtc, int guaranteeMinutes,
                              long actualMinutes, long deficitMinutes,
                              Long windowStartUtc, Long windowEndUtc, long createdAt) {

    /** 接续登记。 */
    public static final String TYPE_REGISTERED = "REGISTERED";
    /** 后车被标记 CONNECTION_AT_RISK。 */
    public static final String TYPE_RISK_MARKED = "RISK_MARKED";
    /** 重估后保障恢复充足，风险标记解除。 */
    public static final String TYPE_RISK_CLEARED = "RISK_CLEARED";
    /** 批量保障改签决策。 */
    public static final String TYPE_RESCHEDULED = "RESCHEDULED";
    /** 接续取消（手动或计划取消/被取代）。 */
    public static final String TYPE_CANCELLED = "CANCELLED";
}
