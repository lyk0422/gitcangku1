package com.example.starter.incident.plan;

import java.time.Instant;

/**
 * 方案版本行。versionNo 事件内递增；baseVersionId 仅 DRAFT 有值（其基版本）；
 * branch 为草稿分支标记（LEFT/RIGHT，仅信息性）；revision 为草稿修订计数器，
 * 每次草稿编辑 +1，合并请求以 expectedVersion 对齐；mergeKey 仅合并产生的版本有值。
 * 所有时间字段均为 UTC。
 */
public record PlanVersion(long id, long incidentId, int versionNo, PlanVersionStatus status,
                          Long baseVersionId, String branch, int revision, String mergeKey,
                          String createdBy, Instant createdAt, Instant publishedAt) {
}
