package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 接续历史事件视图（只读，读取不改变状态）。null 语义与 rail_connection_event 一致：
 * CANCELLED 事件时刻与分钟数字段为 null；windowStartUtc/windowEndUtc 仅 RISK 事件非空。
 */
public record ConnectionEventView(long id, String eventType, long connectionId,
                                  String predecessorKey, String successorKey, int predecessorVersion,
                                  Instant oldTimeUtc, Instant newTimeUtc,
                                  Integer requiredMinutes, Integer actualMinutes,
                                  Integer shortfallMinutes,
                                  Instant windowStartUtc, Instant windowEndUtc,
                                  Instant createdAt) {
}