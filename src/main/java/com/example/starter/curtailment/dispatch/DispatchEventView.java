package com.example.starter.curtailment.dispatch;

import java.time.Instant;

/**
 * 调度历史事件视图。
 *
 * @param eventType  事件类型：CREATED/ALLOCATIONS_REPLACED/PUBLISHED/CANCELLED
 * @param version    事件发生后的调度版本
 * @param occurredAt 事件时间（UTC）
 */
public record DispatchEventView(String eventType, int version, Instant occurredAt) {
}
