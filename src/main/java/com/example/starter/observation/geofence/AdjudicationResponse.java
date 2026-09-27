package com.example.starter.observation.geofence;

import java.time.Instant;

/**
 * 人工裁决响应：返回不可变裁决记录要点及裁决后的观测状态。
 *
 * @param adjudicationId    全局唯一裁决记录标识
 * @param observationId     被裁决的观测标识
 * @param requestId         生成该裁决的请求标识
 * @param decision          裁决结论：ACCEPT
 * @param reason            裁决理由
 * @param operator          裁决操作者标识
 * @param adjudicatedAtUtc  裁决完成时刻（UTC，ISO-8601）
 * @param observationStatus 裁决后的观测当前状态
 */
public record AdjudicationResponse(
        String adjudicationId,
        String observationId,
        String requestId,
        String decision,
        String reason,
        String operator,
        Instant adjudicatedAtUtc,
        String observationStatus) {

    /**
     * 由裁决记录与裁决后观测状态构造响应。
     */
    public static AdjudicationResponse of(AdjudicationRecord record, String observationStatus) {
        return new AdjudicationResponse(record.adjudicationId(), record.observationId(), record.requestId(),
                record.decision(), record.reason(), record.operator(), record.adjudicatedAtUtc(),
                observationStatus);
    }
}
