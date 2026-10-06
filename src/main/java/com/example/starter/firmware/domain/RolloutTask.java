package com.example.starter.firmware.domain;

/**
 * 设备投放任务，同设备同发布单最多一条。
 *
 * @param id              任务ID
 * @param releaseId       所属发布单ID
 * @param deviceId        设备ID
 * @param status          任务状态
 * @param firstResult     首次回执结果（SUCCESS/FAILED），未回执为 null
 * @param taskType        任务类型：NORMAL 普通任务；BOOT_CHALLENGE 启动挑战任务
 * @param challenge       启动挑战值，BOOT_CHALLENGE 任务领取时生成的合成唯一值，创建后不可改；普通任务为 null
 * @param frozenBootCount 领取时冻结的设备启动计数 b，SUCCESS 回执的计数 c 必须严格大于 b；普通任务为 null
 */
public record RolloutTask(long id, long releaseId, String deviceId, TaskStatus status,
                          ReceiptResult firstResult, TaskType taskType, String challenge,
                          Long frozenBootCount) {
}
