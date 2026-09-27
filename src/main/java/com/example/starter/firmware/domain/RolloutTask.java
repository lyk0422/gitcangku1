package com.example.starter.firmware.domain;

/**
 * 投放任务，同设备同发布单最多一条。创建时固化当前有效证书链版本。
 *
 * @param id               任务ID
 * @param releaseId        所属发布单ID
 * @param deviceId         设备ID
 * @param status           任务状态
 * @param firstResult      首次回执结果（SUCCESS/FAILED），未回执为 null
 * @param certChainVersion 任务创建时固化的证书链版本，目标固件未登记证书链时为 null
 * @param reviewStatus     复核状态
 */
public record RolloutTask(long id, long releaseId, String deviceId, TaskStatus status,
                          ReceiptResult firstResult, String certChainVersion,
                          ReviewStatus reviewStatus) {
}
