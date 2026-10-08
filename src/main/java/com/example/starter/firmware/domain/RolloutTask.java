package com.example.starter.firmware.domain;

import java.util.Map;

/**
 * 设备投放任务，同设备同发布单最多一条。
 *
 * @param id            任务ID
 * @param releaseId     所属发布单ID
 * @param deviceId      设备ID
 * @param status        任务状态
 * @param firstResult   首次回执结果（SUCCESS/FAILED），未回执为 null
 * @param configVersion 领取时冻结的设备配置版本；发布单无迁移规则时为 null
 * @param configBefore  领取时冻结的迁移前完整配置快照（键升序），无迁移规则时为 null
 * @param configAfter   领取时预演通过的迁移后完整配置快照（键升序），SUCCESS 回执整体应用
 */
public record RolloutTask(long id, long releaseId, String deviceId, TaskStatus status,
                          ReceiptResult firstResult, Integer configVersion,
                          Map<String, Integer> configBefore, Map<String, Integer> configAfter) {
}
