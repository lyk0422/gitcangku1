package com.example.starter.api.dto;

/**
 * 不可变替换链中的一环：source 预留被 target 预留挤出。
 *
 * @param sourceReservationId 被挤出的预留标识
 * @param targetReservationId 挤出方预留标识
 * @param targetRouteId       挤出方航线标识
 * @param targetPriority      挤出方优先级
 * @param targetStatus        挤出方当前状态（可能已起飞/再被挤出；替换关系本身不可变）
 */
public record ReplacementLink(String sourceReservationId, String targetReservationId,
                              String targetRouteId, int targetPriority, String targetStatus) {
}
