package com.example.starter.observation;

import java.time.Instant;

/**
 * 待复审标记：已人工裁决的观测再收到新附页时生成，对应 re_review_marker 表的一行。
 *
 * @param resolutionId  被冻结的冲突解决记录标识
 * @param observationId 观测记录唯一标识
 * @param corrVersion   触发该标记的附页版本号
 * @param status        标记状态：PENDING 表示待复审
 * @param createdAtUtc  标记生成时刻（UTC）
 */
public record ReReviewMarker(
        String resolutionId,
        String observationId,
        int corrVersion,
        String status,
        Instant createdAtUtc) {
}
