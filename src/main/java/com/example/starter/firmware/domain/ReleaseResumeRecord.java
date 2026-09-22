package com.example.starter.firmware.domain;

import java.time.Instant;

/**
 * 发布单人工恢复记录，历史不可改。
 *
 * @param id           恢复记录ID
 * @param releaseId    所属发布单ID
 * @param monitorRound 本次恢复开启的新监控轮次
 * @param version      恢复后的发布单版本号
 * @param reason       人工恢复原因
 * @param resumedAt    恢复时刻（UTC）
 */
public record ReleaseResumeRecord(long id, long releaseId, int monitorRound, int version,
                                  String reason, Instant resumedAt) {
}
