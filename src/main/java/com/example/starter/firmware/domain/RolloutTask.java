package com.example.starter.firmware.domain;

/**
 * 设备投放任务尝试，同设备同发布单每代尝试一条；完整性失败后重新拉取建立新代次，旧代次保留。
 *
 * @param id              任务尝试ID
 * @param releaseId       所属发布单ID
 * @param deviceId        设备ID
 * @param attemptNo       尝试代次，从1开始
 * @param status          任务状态
 * @param firstResult     首次安装回执结果（SUCCESS/FAILED），未回执为 null
 * @param aggregateDigest 可安装判定时刻固化的聚合摘要，未判定为 null
 */
public record RolloutTask(long id, long releaseId, String deviceId, int attemptNo, TaskStatus status,
                          ReceiptResult firstResult, String aggregateDigest) {
}
