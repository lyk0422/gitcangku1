package com.example.starter.firmware.domain;

/**
 * 设备投放任务，同设备同发布单最多一条。创建时固化拉取时刻的证书链版本。
 *
 * @param id           任务ID
 * @param releaseId    所属发布单ID
 * @param deviceId     设备ID
 * @param status       任务状态
 * @param firstResult  首次回执结果（SUCCESS/FAILED），未回执为 null
 * @param chainId      拉取时固化的证书链ID，无证书要求时为 null
 * @param chainVersion 拉取时固化的证书链版本，无证书要求时为 null
 * @param reviewStatus 回执复核标记，无复核为 null
 */
public record RolloutTask(long id, long releaseId, String deviceId, TaskStatus status,
                          ReceiptResult firstResult, Long chainId, String chainVersion,
                          ReviewStatus reviewStatus) {
}
