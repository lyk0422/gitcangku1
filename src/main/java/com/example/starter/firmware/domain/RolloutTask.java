package com.example.starter.firmware.domain;

/**
 * 设备投放任务，同设备同发布单最多一条。
 *
 * @param id            任务ID
 * @param releaseId     所属发布单ID
 * @param deviceId      设备ID
 * @param status        任务状态
 * @param firstResult   首次回执结果（SUCCESS/FAILED），未回执为 null
 * @param reservedBytes 暂存预留字节数（压缩包+新镜像），PENDING 期间占用，任务终结时释放；旧模式为 null
 * @param peakBytes     领取时峰值字节数（当时旧镜像+包+新镜像），历史快照不漂移；旧模式为 null
 */
public record RolloutTask(long id, long releaseId, String deviceId, TaskStatus status,
                          ReceiptResult firstResult, Long reservedBytes, Long peakBytes) {
}
