package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 隔离历史事件视图（创建 / 生效 / 解除），按事件序号稳定排序。
 *
 * @param seq          事件序号（从 1 起，稳定排序）
 * @param orderId      隔离令标识
 * @param equipmentId  设备标识
 * @param eventType    事件类型：CREATED / ACTIVATED / RELEASED
 * @param operatorId   事件操作人；生效事件为最后一名确认人，创建/解除为提交人
 * @param requestId    产生该事件的请求 requestId
 * @param occurredAt   事件发生时刻（UTC）
 * @param reason       仅解除事件有值（解除原因），其余为 null
 */
public record IsolationHistoryEvent(
        long seq,
        String orderId,
        String equipmentId,
        String eventType,
        String operatorId,
        String requestId,
        Instant occurredAt,
        String reason) {
}
