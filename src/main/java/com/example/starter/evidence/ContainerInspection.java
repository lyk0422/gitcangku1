package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 容器巡检记录实体，对应 container_inspection 表。
 * requestId 为幂等键：同键同参重放返回首次结果，同键换封条结果或版本返回 409。
 *
 * @param id               主键
 * @param requestId        巡检请求键（幂等键），全局唯一
 * @param containerKey     关联容器业务键
 * @param containerVersion 首次见证的容器版本
 * @param sealIntact       封条结果：true 完好 / false 异常
 * @param status           巡检状态
 * @param firstInspector   首位巡检保管人
 * @param secondInspector  第二位巡检保管人；null 表示待第二人确认
 * @param confirmedAt      双人确认完成时刻（Asia/Shanghai）；null 表示未完成
 * @param responseStatus   最近一次状态变更执行的 HTTP 状态码
 * @param responseBody     最近一次状态变更执行的响应体 JSON（含首次隔离清单）
 * @param createdAt        首次提交时间（Asia/Shanghai）
 */
public record ContainerInspection(
        Long id,
        String requestId,
        String containerKey,
        long containerVersion,
        boolean sealIntact,
        InspectionStatus status,
        String firstInspector,
        String secondInspector,
        LocalDateTime confirmedAt,
        int responseStatus,
        String responseBody,
        LocalDateTime createdAt) {
}
