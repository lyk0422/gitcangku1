package com.example.starter.firmware.domain;

/**
 * 设备投放任务，同设备同发布单最多一条。
 *
 * @param id              任务ID
 * @param releaseId       所属发布单ID
 * @param deviceId        设备ID
 * @param status          任务状态
 * @param firstResult     首次回执结果（SUCCESS/FAILED），未回执为 null
 * @param taskType        任务类型：NORMAL 普通；BOOT_CHALLENGE 启动挑战
 * @param challenge       领取时生成的唯一挑战串，仅 BOOT_CHALLENGE 任务非空，历史不被下一任务覆盖
 * @param frozenBootCount 领取时冻结的设备启动计数基线 b，仅 BOOT_CHALLENGE 任务非空
 */
public record RolloutTask(long id, long releaseId, String deviceId, TaskStatus status,
                          ReceiptResult firstResult, TaskType taskType, String challenge,
                          Long frozenBootCount) {
}
