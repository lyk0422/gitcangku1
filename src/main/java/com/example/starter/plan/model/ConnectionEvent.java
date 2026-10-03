package com.example.starter.plan.model;

/**
 * 接续历史事件（只追加、不改写）：登记、风险判定与保障改签决策的证据快照。
 * 后续取消接续或再次改签均不回写已固化记录。
 *
 * @param id                 主键
 * @param connectionId       所属接续 id
 * @param eventType          事件类型：REGISTERED / CANCELLED / RISK_MARKED / RESCHEDULE_DECIDED
 * @param predecessorPlanId  事件固化时的前车计划 id
 * @param successorPlanId    事件固化时的后车计划 id
 * @param predecessorVersion 事件固化所依据的前车版本
 * @param oldTimeUtc         事件前基准时刻（风险：前车原终到；决策：后车原始发），UTC 毫秒，可空
 * @param newTimeUtc         事件后时刻（风险：前车新终到；决策：后车新始发），UTC 毫秒，可空
 * @param requiredMinutes    要求保障分钟数，可空
 * @param actualMinutes      实际保障分钟数，可空
 * @param deficitMinutes     保障差额（要求减实际，正数为不足），可空
 * @param windowStartUtc     可选改签时间窗起点（含），UTC 毫秒，可空
 * @param windowEndUtc       可选改签时间窗终点（不含），UTC 毫秒，可空
 * @param createdAt          事件记录时刻，UTC 毫秒
 */
public record ConnectionEvent(long id, long connectionId, String eventType,
                              long predecessorPlanId, long successorPlanId,
                              int predecessorVersion, Long oldTimeUtc, Long newTimeUtc,
                              Integer requiredMinutes, Integer actualMinutes, Integer deficitMinutes,
                              Long windowStartUtc, Long windowEndUtc, long createdAt) {
}