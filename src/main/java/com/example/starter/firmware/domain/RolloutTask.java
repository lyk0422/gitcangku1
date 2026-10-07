package com.example.starter.firmware.domain;

/**
 * 投放任务，同设备同发布单最多一条。领取时冻结配置版本与迁移前后完整快照。
 *
 * @param id                  任务ID
 * @param releaseId           所属发布单ID
 * @param deviceId            设备ID
 * @param status              任务状态
 * @param firstResult         首次回执结果（SUCCESS/FAILED），未回执为 null
 * @param configVersionBefore 领取时冻结的设备配置版本
 * @param configBefore        领取时冻结的迁移前完整配置快照 JSON（按键排序）
 * @param configAfter         领取时预演通过的迁移后完整配置快照 JSON；SUCCESS 回执时整体应用
 * @param failureReason       首次 FAILED 回执携带的失败原因，冻结后不可改，未提供为 null
 */
public record RolloutTask(long id, long releaseId, String deviceId, TaskStatus status,
                          ReceiptResult firstResult, Integer configVersionBefore,
                          String configBefore, String configAfter, String failureReason) {
}
