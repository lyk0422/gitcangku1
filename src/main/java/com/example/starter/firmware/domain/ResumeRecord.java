package com.example.starter.firmware.domain;

/**
 * 发布单人工恢复记录，历史不可改。
 *
 * @param id           恢复记录ID
 * @param releaseId    所属发布单ID
 * @param newRound     本次恢复开启的新监控轮次
 * @param reason       人工恢复原因
 * @param resumedAtUtc 恢复时刻，UTC，ISO-8601 格式
 */
public record ResumeRecord(long id, long releaseId, int newRound, String reason, String resumedAtUtc) {
}
