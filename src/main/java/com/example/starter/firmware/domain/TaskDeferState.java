package com.example.starter.firmware.domain;

/**
 * 任务顺延累计状态，与同发布单同设备的任务一一对应（任务可能尚未创建，仍在顺延期）。
 * 只增不清零：设备窗口修订、发布单人工恢复均不重置。
 *
 * @param releaseId         所属发布单ID
 * @param deviceId          设备ID
 * @param deferCount        累计顺延次数，窗口外拉取一次计一次
 * @param lastDeferredAtUtc 最近顺延时刻，UTC，ISO-8601 格式
 */
public record TaskDeferState(long releaseId, String deviceId, int deferCount, String lastDeferredAtUtc) {
}
