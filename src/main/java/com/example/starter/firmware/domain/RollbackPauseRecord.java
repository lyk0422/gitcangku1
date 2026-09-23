package com.example.starter.firmware.domain;

/**
 * 回退计划自动暂停记录，每个监控轮次至多一条，历史不可改。
 *
 * @param id               暂停记录ID
 * @param planId           所属回退计划ID
 * @param monitorRound     触发暂停的监控轮次
 * @param hopIndex         触发暂停的跳次
 * @param triggerHopTaskId 触发暂停的回执回跳任务ID
 * @param successCount     暂停时刻该跳本轮成功样本数
 * @param failedCount      暂停时刻该跳本轮失败样本数
 * @param pausedAtUtc      暂停时刻，UTC，ISO-8601 格式
 */
public record RollbackPauseRecord(long id, long planId, int monitorRound, int hopIndex,
                                  long triggerHopTaskId, int successCount, int failedCount,
                                  String pausedAtUtc) {
}
