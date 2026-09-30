package com.example.starter.repo;

/**
 * 不可变预留替换（挤出）事件 PO。一次高优先级申请挤出多条低优先级预留时，
 * 每个被挤出预留对应一行，与新预留记录在同一事务内一次写入。
 *
 * @param replacementId           替换事件唯一标识（不可变）
 * @param airportId               发生替换的机场标识
 * @param replacedReservationId   被挤出预留标识
 * @param replacedRouteId         被挤出预留所属航线
 * @param replacedPriority        被挤出预留优先级快照
 * @param replacementReservationId 挤出方（新预留）标识
 * @param replacementRouteId      挤出方所属航线
 * @param replacementPriority     挤出方优先级快照
 * @param windowStart             被挤出预留窗口起点快照，epoch 毫秒（UTC），左闭
 * @param windowEnd               被挤出预留窗口终点快照，epoch 毫秒（UTC），右开
 * @param createdAt               事件发生时间，epoch 毫秒（UTC）
 */
public record ReplacementPo(String replacementId, String airportId,
                            String replacedReservationId, String replacedRouteId, int replacedPriority,
                            String replacementReservationId, String replacementRouteId,
                            int replacementPriority, long windowStart, long windowEnd,
                            long createdAt) {
}
