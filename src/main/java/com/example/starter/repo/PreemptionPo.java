package com.example.starter.repo;

import java.util.List;

/**
 * 紧急抢占不可变快照头记录。
 *
 * @param preemptionId          抢占记录标识
 * @param emergencyClearanceId  紧急批件标识
 * @param emergencyRouteId      紧急航线标识
 * @param eventNo               紧急事件编号
 * @param airspaceVersion       裁决时空域版本（冻结）
 * @param displacedRouteIds     全部被置换航线标识（字典序去重，冻结）
 * @param requestId             紧急提交请求标识
 * @param createdAt             裁决时间（epoch 毫秒，UTC）
 */
public record PreemptionPo(
        String preemptionId,
        String emergencyClearanceId,
        String emergencyRouteId,
        String eventNo,
        long airspaceVersion,
        List<String> displacedRouteIds,
        String requestId,
        long createdAt) {
}
