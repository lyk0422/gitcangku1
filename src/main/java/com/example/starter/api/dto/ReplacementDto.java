package com.example.starter.api.dto;

/**
 * 不可变预留替换（挤出）事件。字段为事件发生时的快照，永不更新；
 * 被挤出预留后续起飞等状态变化不改写本事件。
 *
 * @param replacementId            替换事件唯一标识
 * @param airportId                发生替换的机场标识
 * @param replacedReservationId    被挤出预留标识
 * @param replacedRouteId          被挤出预留所属航线
 * @param replacedPriority         被挤出预留优先级（低于挤出方）
 * @param replacementReservationId 挤出方（新预留）标识
 * @param replacementRouteId       挤出方所属航线
 * @param replacementPriority      挤出方优先级
 * @param windowStart              被挤出预留窗口起点快照，epoch 毫秒（UTC），左闭
 * @param windowEnd                被挤出预留窗口终点快照，epoch 毫秒（UTC），右开
 * @param createdAt                事件发生时间，epoch 毫秒（UTC）
 */
public record ReplacementDto(
        String replacementId,
        String airportId,
        String replacedReservationId,
        String replacedRouteId,
        int replacedPriority,
        String replacementReservationId,
        String replacementRouteId,
        int replacementPriority,
        long windowStart,
        long windowEnd,
        long createdAt) {
}
