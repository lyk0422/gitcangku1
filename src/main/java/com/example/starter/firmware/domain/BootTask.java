package com.example.starter.firmware.domain;

/**
 * 双分区试启动任务。同设备同发布单允许多次尝试（回滚后重试产生新任务ID），
 * 由 (release_id, device_id, attempt_no) 唯一约束保证尝试序号不重复。
 *
 * @param id                任务ID
 * @param releaseId         所属双分区发布单ID
 * @param deviceId          设备ID
 * @param attemptNo         同设备同发布单内试启动尝试序号，从1开始
 * @param status            任务状态
 * @param targetPartition   试启动目标分区（非当前分区），进入 TRIAL 时记录，之后不可改；未试启动为 null
 * @param targetVersion     试启动目标版本，进入 TRIAL 时记录，之后不可改；未试启动为 null
 * @param trialStartedAtUtc 试启动时刻（UTC，ISO-8601，服务端时钟），进入 TRIAL 时记录；未试启动为 null
 */
public record BootTask(long id, long releaseId, String deviceId, int attemptNo, BootTaskStatus status,
                       String targetPartition, String targetVersion, String trialStartedAtUtc) {
}
