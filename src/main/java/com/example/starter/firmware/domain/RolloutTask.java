package com.example.starter.firmware.domain;

/**
 * 设备投放任务。普通灰度任务同设备同发布单最多一条（attemptNo 恒为 1）；
 * 双分区任务回滚后重试会产生新任务ID（attemptNo 递增）。
 *
 * @param id                任务ID
 * @param releaseId         所属发布单ID
 * @param deviceId          设备ID
 * @param status            任务状态
 * @param firstResult       首次回执结果（SUCCESS/FAILED），未回执为 null
 * @param attemptNo         投放尝试序号，同设备同发布单从 1 开始，回滚后重试加一
 * @param targetPartition   试启动目标分区（A/B），仅双分区任务进入 TRIAL 时记录，之后不可改
 * @param targetVersion     试启动目标版本，仅双分区任务进入 TRIAL 时记录，之后不可改
 * @param trialStartedAtUtc 试启动时刻，UTC，ISO-8601 格式，由服务端时钟记录；非 TRIAL 任务为 null
 */
public record RolloutTask(long id, long releaseId, String deviceId, TaskStatus status,
                          ReceiptResult firstResult, int attemptNo, String targetPartition,
                          String targetVersion, String trialStartedAtUtc) {
}