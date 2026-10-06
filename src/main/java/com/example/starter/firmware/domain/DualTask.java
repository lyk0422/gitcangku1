package com.example.starter.firmware.domain;

/**
 * 双分区试启动任务。试启动字段（目标分区、目标版本、试启动时刻）在进入 TRIAL 时冻结，之后不可改。
 *
 * @param id                任务ID，回滚后重试产生新ID
 * @param releaseId         所属双分区发布单ID
 * @param deviceId          设备ID
 * @param status            任务状态
 * @param targetPartition   目标分区（非当前分区），未试启动为 null
 * @param targetVersion     目标版本，未试启动为 null
 * @param trialStartedAtUtc 服务端记录的 UTC 试启动时刻（ISO-8601），未试启动为 null
 * @param settledAtUtc      进入终态的 UTC 时刻（ISO-8601），未终结为 null
 * @param rollbackReason    回滚原因，未回滚为 null
 */
public record DualTask(long id, long releaseId, String deviceId, DualTaskStatus status,
                       String targetPartition, String targetVersion, String trialStartedAtUtc,
                       String settledAtUtc, RollbackReason rollbackReason) {
}
