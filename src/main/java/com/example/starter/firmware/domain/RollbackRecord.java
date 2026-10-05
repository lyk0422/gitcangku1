package com.example.starter.firmware.domain;

/**
 * 双分区试启动回滚记录，每个任务至多一条，历史不可改。
 *
 * @param id              回滚记录ID
 * @param taskId          回滚的双分区任务ID
 * @param releaseId       所属发布单ID
 * @param deviceId        设备ID
 * @param keptPartition   回滚后保留的旧启动分区（A/B）
 * @param keptVersion     回滚后保留的旧固件版本
 * @param reason          回滚原因：EXPIRED 到期未确认，FAILED 设备回报失败
 * @param rolledBackAtUtc 回滚时刻，UTC，ISO-8601 格式
 */
public record RollbackRecord(long id, long taskId, long releaseId, String deviceId,
                             String keptPartition, String keptVersion, RollbackReason reason,
                             String rolledBackAtUtc) {
}