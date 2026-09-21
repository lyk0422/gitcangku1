package com.example.starter.curtailment.dispatch;

import java.time.Instant;

/**
 * 调度状态历史事件。
 *
 * @param id         主键
 * @param dispatchId 所属调度ID
 * @param eventType  事件类型：CREATED/ALLOCATIONS_REPLACED/PUBLISHED/CANCELLED
 * @param version    事件发生后的调度版本
 * @param createdAt  事件时间（UTC）
 */
public record DispatchEvent(long id, long dispatchId, String eventType, int version, Instant createdAt) {
}
