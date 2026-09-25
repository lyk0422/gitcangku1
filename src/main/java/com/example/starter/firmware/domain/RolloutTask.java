package com.example.starter.firmware.domain;

/**
 * 设备投放任务，同设备同发布单最多一条。
 *
 * @param id            任务ID
 * @param releaseId     所属发布单ID
 * @param deviceId      设备ID
 * @param status        任务状态
 * @param firstResult   首次回执结果（SUCCESS/FAILED），未回执为 null
 * @param compatVersion 拉取创建任务时目标固件的兼容矩阵版本；未配置矩阵时为 0
 */
public record RolloutTask(long id, long releaseId, String deviceId, TaskStatus status,
                          ReceiptResult firstResult, int compatVersion) {
}
