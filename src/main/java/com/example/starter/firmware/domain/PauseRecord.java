package com.example.starter.firmware.domain;

/**
 * 发布单自动暂停记录，每个监控轮次至多一条，历史不可改。
 *
 * @param id            暂停记录ID
 * @param releaseId     所属发布单ID
 * @param monitorRound  触发暂停的监控轮次
 * @param triggerTaskId 触发暂停的回执任务ID
 * @param successCount  暂停时刻本轮成功样本数
 * @param failedCount   暂停时刻本轮失败样本数
 * @param pausedAtUtc   暂停时刻，UTC，ISO-8601 格式
 */
public record PauseRecord(long id, long releaseId, int monitorRound, long triggerTaskId,
                          int successCount, int failedCount, String pausedAtUtc) {
}
