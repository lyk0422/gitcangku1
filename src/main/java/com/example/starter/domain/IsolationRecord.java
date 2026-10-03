package com.example.starter.domain;

import java.time.Instant;

/**
 * 异常电芯隔离记录。
 *
 * @param id 隔离记录主键
 * @param cellId 被隔离电芯主键
 * @param reason 隔离原因
 * @param isolatedAt 隔离时刻，UTC
 */
public record IsolationRecord(long id, long cellId, String reason, Instant isolatedAt) {
}