package com.example.starter.api.dto;

/**
 * 被置换航线信息（紧急抢占结果与查询共用）。
 *
 * @param routeId                 被置换 NORMAL 航线标识
 * @param displacedClearanceId    被置换批件标识（快照冻结）
 * @param routeVersionSnapshot    被置换时的航线版本（快照冻结）
 * @param status                  PENDING 未处理 / RESOLVED 已重新批准
 * @param resolvedClearanceId     重新提交后的新批件标识；未处理为 null
 */
public record DisplacedRouteDto(
        String routeId,
        String displacedClearanceId,
        Integer routeVersionSnapshot,
        String status,
        String resolvedClearanceId) {
}
