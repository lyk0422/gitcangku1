package com.example.starter.firmware.domain;

import java.time.Instant;

/**
 * 发布单失败率自动暂停记录，每个监控轮次最多一条，历史不可改。
 *
 * @param id            暂停记录ID
 * @param releaseId     所属发布单ID
 * @param monitorRound  暂停发生的监控轮次
 * @param triggerTaskId 触发暂停的回执任务ID
 * @param successCount  暂停时本轮成功样本数
 * @param failureCount  暂停时本轮失败样本数
 * @param pausedAt      暂停时刻（UTC）
 */
public record ReleasePauseRecord(long id, long releaseId, int monitorRound, long triggerTaskId,
                                 int successCount, int failureCount, Instant pausedAt) {
}
