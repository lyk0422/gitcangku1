package com.example.starter.api.dto;

import java.util.List;

/**
 * 优先级审查结果。NORMAL 批准或 EMERGENCY 抢占成功时返回；
 * 被置换航线列表与不可变抢占快照一同返回（NORMAL 时为空）。
 *
 * @param clearanceId   新生效批件标识
 * @param reviewId      关联的不可变审核记录标识
 * @param routeId       航线标识
 * @param routeVersion  批准时航线版本
 * @param airspaceVersion 批准时空域版本
 * @param priority      NORMAL / EMERGENCY
 * @param eventNo       紧急事件编号；NORMAL 为 null
 * @param buckets       规范化时空桶文本（cellX,cellY,timeBucket；字典序去重）
 * @param displaced     本次抢占置换的航线（NORMAL 审查为空列表）
 * @param preemptionId  抢占快照标识；NORMAL 审查为 null
 */
public record PriorityReviewResultDto(
        String clearanceId,
        String reviewId,
        String routeId,
        Integer routeVersion,
        Long airspaceVersion,
        String priority,
        String eventNo,
        List<String> buckets,
        List<DisplacedRouteDto> displaced,
        String preemptionId) {
}
