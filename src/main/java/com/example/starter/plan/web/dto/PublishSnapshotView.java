package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 发布快照明细视图：快照列与固化正文的组合。operator 未携带时为 null。
 */
public record PublishSnapshotView(String scheduleKey, String opType, String conclusion,
                                  String operator, Instant publishedAt, SnapshotBody snapshot) {
}
