package com.example.starter.firmware.domain;

/**
 * 设备投放任务，同设备同发布单最多一条。
 *
 * @param id               任务ID
 * @param releaseId        所属发布单ID
 * @param deviceId         设备ID
 * @param status           任务状态
 * @param firstResult      首次回执结果（SUCCESS/FAILED），未回执为 null
 * @param cancelGeneration 取消代次，每次取消请求加一，停止确认须携带同代次；未请求取消为 null
 * @param cancelOperator   最近一次取消请求的操作者
 */
public record RolloutTask(long id, long releaseId, String deviceId, TaskStatus status,
                          ReceiptResult firstResult, Integer cancelGeneration, String cancelOperator) {
}
