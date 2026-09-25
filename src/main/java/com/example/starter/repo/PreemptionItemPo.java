package com.example.starter.repo;

/**
 * 抢占快照明细记录（每被置换 NORMAL 航线一条）。
 * 快照字段不可变；status/pendingKey/resolved* 随该航线重新批准推进。
 *
 * @param id                     自增主键
 * @param preemptionId           所属抢占快照标识
 * @param displacedRouteId       被置换航线标识
 * @param displacedClearanceId   被置换批件标识（冻结）
 * @param routeVersionSnapshot   被置换时航线版本（冻结）
 * @param bucketsCanonical       被置换航线规范化时空桶（冻结）
 * @param status                 PENDING / RESOLVED
 * @param pendingKey             PENDING 时等于航线标识，否则 null
 * @param createdAt              抢占发生时间（epoch 毫秒，UTC）
 * @param resolvedAt             重新批准时间；null 未处理
 * @param resolvedClearanceId    重新批准的新批件标识；null 未处理
 */
public record PreemptionItemPo(
        Long id,
        String preemptionId,
        String displacedRouteId,
        String displacedClearanceId,
        int routeVersionSnapshot,
        String bucketsCanonical,
        String status,
        String pendingKey,
        long createdAt,
        Long resolvedAt,
        String resolvedClearanceId) {
}
