package com.example.starter.maintenance.model;

import java.time.Instant;

/**
 * 工时读数：设备内 readingId 唯一，同设备同一采样时刻仅一条。
 *
 * @param readingId          设备内唯一读数标识
 * @param sampledAt          UTC 采样时刻
 * @param accumulatedMinutes 当前累计工时，单位分钟，非负整数
 * @param currentRevision    当前修订号，初始1
 */
public record Reading(String readingId, Instant sampledAt, long accumulatedMinutes, int currentRevision) {
}
