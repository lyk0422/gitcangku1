package com.example.starter.firmware.domain;

/**
 * 设备投放任务，同设备同发布单每次尝试一条，失败显式重试追加新行。
 *
 * @param id          任务ID
 * @param releaseId   所属发布单ID
 * @param deviceId    设备ID
 * @param status      任务状态
 * @param firstResult 首次回执结果（SUCCESS/FAILED），未回执为 null
 * @param attemptNo   尝试序号，从1开始，原任务为第1次，最多3次
 * @param prevTaskId  前驱任务ID，首次尝试为 null，重试任务指向上一次尝试
 */
public record RolloutTask(long id, long releaseId, String deviceId, TaskStatus status,
                          ReceiptResult firstResult, int attemptNo, Long prevTaskId) {
}
