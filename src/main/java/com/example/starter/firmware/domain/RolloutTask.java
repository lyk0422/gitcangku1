package com.example.starter.firmware.domain;

/**
 * 设备投放任务，同设备同发布单最多一条。
 * 模块摘要、矩阵版本、豁免版本为创建任务时的快照，后续上报变化或矩阵修订不改写。
 *
 * @param id               任务ID
 * @param releaseId        所属发布单ID
 * @param deviceId         设备ID
 * @param status           任务状态
 * @param firstResult      首次回执结果（SUCCESS/FAILED），未回执为 null
 * @param moduleDigest     创建任务时设备模块摘要快照，设备从未上报为 null
 * @param matrixVersion    创建任务时兼容矩阵版本快照
 * @param exemptionVersion 创建任务时发布单豁免版本快照
 */
public record RolloutTask(long id, long releaseId, String deviceId, TaskStatus status,
                          ReceiptResult firstResult, String moduleDigest,
                          Integer matrixVersion, Integer exemptionVersion) {
}
