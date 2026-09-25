package com.example.starter.firmware.domain;

/**
 * 投放任务，同设备同发布单最多一条。创建与完成时分别固化发布快照；
 * 被冻结令冻结时固化冻结令快照，解冻后清除。
 *
 * @param id                 任务ID
 * @param releaseId          所属发布单ID
 * @param deviceId           设备ID
 * @param status             任务状态
 * @param firstResult        首次回执结果（SUCCESS/FAILED），未回执为 null
 * @param fromVersion        创建时发布快照：来源固件版本
 * @param toVersion          创建时发布快照：目标固件版本
 * @param receiptFromVersion 完成时发布快照：来源固件版本，未回执为 null
 * @param receiptToVersion   完成时发布快照：目标固件版本，未回执为 null
 * @param freezeOrderId      冻结该任务的冻结令ID，未冻结为 null
 * @param freezeSnapshot     冻结时刻冻结令快照（JSON），未冻结为 null
 */
public record RolloutTask(long id, long releaseId, String deviceId, TaskStatus status,
                          ReceiptResult firstResult, String fromVersion, String toVersion,
                          String receiptFromVersion, String receiptToVersion,
                          Long freezeOrderId, String freezeSnapshot) {
}
