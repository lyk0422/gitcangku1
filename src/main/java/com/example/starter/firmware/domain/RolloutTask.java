package com.example.starter.firmware.domain;

/**
 * 设备投放任务，同设备同发布单最多一条。
 *
 * @param id          任务ID
 * @param releaseId   所属发布单ID
 * @param deviceId    设备ID
 * @param status      任务状态
 * @param firstResult 首次回执结果（SUCCESS/FAILED），未回执为 null
 * @param targetIndex 任务创建时冻结的目标安全索引，null 表示旧发布不适用
 * @param proofIndex  安装证据索引，SUCCESS 回执时写入且与冻结目标一致，未回执或 FAILED 为 null
 */
public record RolloutTask(long id, long releaseId, String deviceId, TaskStatus status,
                          ReceiptResult firstResult, Integer targetIndex, Integer proofIndex) {
}
