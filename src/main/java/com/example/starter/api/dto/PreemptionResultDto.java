package com.example.starter.api.dto;

import java.util.List;

/**
 * 抢占不可变快照查询结果（冻结字段永不改写；item 处理状态除外）。
 *
 * @param preemptionId          抢占记录标识
 * @param emergencyClearanceId  紧急批件标识
 * @param emergencyRouteId      紧急航线标识
 * @param eventNo               紧急事件编号
 * @param airspaceVersion       裁决时空域版本（冻结）
 * @param displacedRouteIds     全部被置换航线标识（字典序去重，冻结）
 * @param createdAt             裁决时间，epoch 毫秒（UTC）
 * @param items                 被置换航线明细（含处理状态）
 */
public record PreemptionResultDto(
        String preemptionId,
        String emergencyClearanceId,
        String emergencyRouteId,
        String eventNo,
        Long airspaceVersion,
        List<String> displacedRouteIds,
        Long createdAt,
        List<DisplacedRouteDto> items) {
}
