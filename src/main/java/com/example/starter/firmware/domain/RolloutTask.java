package com.example.starter.firmware.domain;

/**
 * 设备投放任务，同设备同发布单最多一条。
 *
 * @param id               任务ID
 * @param releaseId        所属发布单ID
 * @param deviceId         设备ID
 * @param status           任务状态
 * @param attempt          接收尝试代次，从1开始；完整性失败后重新拉取加一
 * @param firstResult      首次安装回执结果（SUCCESS/FAILED），未回执为 null；完整性失败不写
 * @param integrityReason  最近一次完整性失败原因代码，PENDING/可安装/已安装时为 null
 * @param installableAtUtc 最近一次判定可安装的 UTC 时刻（ISO-8601），从未可安装为 null
 */
public record RolloutTask(long id, long releaseId, String deviceId, TaskStatus status, int attempt,
                          ReceiptResult firstResult, String integrityReason, String installableAtUtc) {
}
