package com.example.starter.observation.geofence;

import java.time.Instant;

/**
 * 人工裁决记录：对应 geofence_adjudication 表的一行，落库后不可变。
 *
 * @param adjudicationId   全局唯一裁决记录标识
 * @param observationId    被裁决的观测标识
 * @param requestId        生成该裁决的请求标识
 * @param decision         裁决结论：ACCEPT
 * @param reason           裁决理由
 * @param operator         裁决操作者标识
 * @param adjudicatedAtUtc 裁决完成时刻（UTC）
 */
public record AdjudicationRecord(
        String adjudicationId,
        String observationId,
        String requestId,
        String decision,
        String reason,
        String operator,
        Instant adjudicatedAtUtc) {
}
