package com.example.starter.firmware.domain;

/**
 * 双分区试启动任务。仅本题新建的双分区发布使用。
 * 试启动字段（目标分区、目标版本、试启动时刻）进入 TRIAL 时记录，同任务不可改；
 * 截止时刻由服务端时钟按试启动时刻加 60 秒计算，不信任设备上报。
 *
 * @param id                任务ID，回滚后重试产生新ID
 * @param releaseId         所属双分区发布单ID
 * @param deviceId          设备ID
 * @param status            任务状态
 * @param targetPartition   试启动目标分区（A/B），必须为非当前分区，未试启动为 null
 * @param targetVersion     试启动目标版本，未试启动为 null
 * @param trialStartedAtUtc 试启动时刻，UTC，ISO-8601，来自服务端时钟，未试启动为 null
 * @param trialDeadlineUtc  健康确认截止时刻，UTC，ISO-8601，未试启动为 null
 */
public record DualBootTask(long id, long releaseId, String deviceId, DualBootTaskStatus status,
                           String targetPartition, String targetVersion,
                           String trialStartedAtUtc, String trialDeadlineUtc) {
}