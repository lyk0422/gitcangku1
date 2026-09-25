package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 待复审标记响应。
 *
 * @param flagId        待复审标记标识
 * @param observationId 观测记录唯一标识
 * @param resolutionId  被标记的裁决记录标识
 * @param corrVersion   触发标记的附页版本号
 * @param event         触发事件：SUBMIT / REVOKE
 * @param status        标记状态：PENDING
 * @param createdAtUtc  标记生成时刻（UTC，ISO-8601）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReviewFlagResponse(
        long flagId,
        String observationId,
        String resolutionId,
        int corrVersion,
        String event,
        String status,
        Instant createdAtUtc) {

    /**
     * 由标记记录构造响应。
     */
    public static ReviewFlagResponse of(ReviewFlagRecord record) {
        return new ReviewFlagResponse(record.flagId(), record.observationId(), record.resolutionId(),
                record.corrVersion(), record.event(), record.status(), record.createdAtUtc());
    }
}
