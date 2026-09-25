package com.example.starter.incident.plan;

import java.time.Instant;

/**
 * 方案版本。baseVersionId：DRAFT 为分支来源、合并结果为合并基准，初始版本为 null；
 * branchSide 仅 DRAFT 有值（LEFT/RIGHT，按创建顺序分配）；
 * revision 为草稿修订计数（每次草稿修改 +1），合并请求以 expectedVersion 比对；
 * leftVersionId/rightVersionId 仅合并产生的 PUBLISHED 版本有值。
 */
public record PlanVersion(long id, String incidentKey, int versionNo, PlanVersionStatus status,
                          Long baseVersionId, String branchSide, int revision,
                          Long leftVersionId, Long rightVersionId,
                          String createdBy, Instant createdAt, Instant publishedAt) {
}
