package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 容器巡检单实体，对应 container_inspection 表。两名见证人共用同一 requestId。
 *
 * @param id                   主键
 * @param requestId            巡检请求幂等键，全局唯一
 * @param containerKey         被巡检容器业务键
 * @param containerSealVersion 建单时见证的容器封条版本快照
 * @param sealResult           封条结论（建单人结论；两人结论不一致时巡检单作废）
 * @param status               巡检单状态
 * @param completedResponse    双人确认完成时首次响应体 JSON；null 表示尚未完成
 * @param completedAt          双人确认完成时间（Asia/Shanghai）；null 表示仍仅一人确认
 * @param createdAt            第一见证人建单时间（Asia/Shanghai）
 */
public record ContainerInspection(
        Long id,
        String requestId,
        String containerKey,
        Integer containerSealVersion,
        SealResult sealResult,
        InspectionStatus status,
        String completedResponse,
        LocalDateTime completedAt,
        LocalDateTime createdAt) {
}
