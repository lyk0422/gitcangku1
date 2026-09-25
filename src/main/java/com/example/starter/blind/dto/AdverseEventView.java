package com.example.starter.blind.dto;

/**
 * 不良事件报告视图：不含席位号与处理代码等盲底信息。
 *
 * @param eventKey       报告业务键
 * @param experimentId   实验编号
 * @param participantId  参与者编号
 * @param severity       严重度：MILD / MODERATE / SEVERE
 * @param description    事件描述
 * @param reporterActor  上报人操作者编号
 * @param createdAt      上报时间，Unix 毫秒，UTC
 */
public record AdverseEventView(
        String eventKey,
        String experimentId,
        String participantId,
        String severity,
        String description,
        String reporterActor,
        long createdAt
) {
}
