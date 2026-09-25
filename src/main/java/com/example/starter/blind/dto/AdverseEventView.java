package com.example.starter.blind.dto;

/**
 * 不良事件报告视图：任何角色可见，但严禁包含处理代码或席位序号。
 *
 * @param id               报告自增主键
 * @param experimentId     实验编号
 * @param participantId    合成参与者编号
 * @param eventKey         事件编号
 * @param severity         严重度 MILD / MODERATE / SEVERE
 * @param description      事件描述
 * @param reporterActor    报告人操作者编号
 * @param reporterRole     报告人角色 COORDINATOR / REVIEWER
 * @param createdAt        报告时间，Unix 毫秒，UTC
 * @param unblindRequestId 已据此报告完成揭盲时的揭盲记录编号；null 表示尚未用于揭盲
 */
public record AdverseEventView(
        long id,
        String experimentId,
        String participantId,
        String eventKey,
        String severity,
        String description,
        String reporterActor,
        String reporterRole,
        long createdAt,
        String unblindRequestId
) {
}
