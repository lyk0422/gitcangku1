package com.example.starter.firmware.domain;

/**
 * 设备投放任务，同设备同发布单最多一条。
 *
 * @param id          任务ID
 * @param releaseId   所属发布单ID
 * @param deviceId    设备ID
 * @param status      任务状态
 * @param firstResult 首次回执结果（SUCCESS/FAILED），未回执为 null
 * @param peakBytes   领取时暂存峰值字节数（旧镜像+压缩包+新镜像），无预留为 null；历史快照不漂移
 */
public record RolloutTask(long id, long releaseId, String deviceId, TaskStatus status,
                          ReceiptResult firstResult, Long peakBytes) {
}
