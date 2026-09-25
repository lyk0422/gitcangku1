package com.example.starter.api.dto;

/**
 * 紧急抢占快照视图（不可变；state 仅随被置换航线重新提交审查而推进）。
 *
 * @param preemptionId          抢占记录唯一标识
 * @param bucketKey             发生抢占的时空桶键
 * @param routeId               被置换的 NORMAL 航线标识
 * @param displacedRouteVersion 被置换时航线版本
 * @param emergencyRouteId      发起抢占的 EMERGENCY 航线标识
 * @param emergencyEventNo      紧急事件编号
 * @param emergencyReviewId     批准紧急航线的审核记录标识
 * @param state                 PENDING / RESUBMITTED
 * @param createdAt             抢占时间（epoch 毫秒，UTC）
 * @param processedAt           重新提交审查时间（epoch 毫秒，UTC）；未处理为 null
 */
public record PreemptionDto(String preemptionId, String bucketKey, String routeId,
                            int displacedRouteVersion, String emergencyRouteId,
                            String emergencyEventNo, String emergencyReviewId,
                            String state, long createdAt, Long processedAt) {
}
