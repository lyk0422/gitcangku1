package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 锁定点确认/解除事件视图（历史流水，按事件自增 id 升序）。
 *
 * @param id         事件自增标识
 * @param orderId    所属隔离令标识
 * @param seqNo      事件时锁定点顺序快照
 * @param pointId    事件时锁定点标识快照
 * @param eventType  CONFIRM / RELEASE
 * @param role       确认角色；解除事件为 null
 * @param operator   操作人
 * @param requestId  产生事件的请求 requestId
 * @param reason     解除原因；确认事件为 null
 * @param createdAt  事件发生时刻（UTC）
 */
public record LockEventView(
        long id,
        String orderId,
        int seqNo,
        String pointId,
        String eventType,
        String role,
        String operator,
        String requestId,
        String reason,
        Instant createdAt) {
}
