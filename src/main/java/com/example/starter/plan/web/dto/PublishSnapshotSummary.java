package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 发布快照历史列表项，按 (createdAt, id) 稳定排序返回。
 */
public record PublishSnapshotSummary(String scheduleKey, String opType, String conclusion,
                                     String operator, Instant publishedAt) {
}
