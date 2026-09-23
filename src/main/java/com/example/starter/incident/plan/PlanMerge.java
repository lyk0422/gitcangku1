package com.example.starter.incident.plan;

import java.time.Instant;

/**
 * 三方合并证据实体，对应 plan_merges 表。
 * mergeKey/requestId 全局唯一；requestHash 为规范化请求参数（冲突解决项按键排序）的摘要；
 * diffJson/resolutionsJson 为发布时冻结的三方差异与全部冲突解决；仅发布成功时写入，失败回滚不占键。
 */
public record PlanMerge(
        long id,
        long planId,
        String mergeKey,
        String requestId,
        String requestHash,
        long baseVersionId,
        long leftVersionId,
        long rightVersionId,
        int leftExpected,
        int rightExpected,
        long resultVersionId,
        String diffJson,
        String resolutionsJson,
        String createdBy,
        Instant createdAt) {
}
