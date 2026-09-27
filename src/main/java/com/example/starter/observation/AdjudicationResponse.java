package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 人工裁决响应：返回不可变裁决记录及裁决后提交状态。
 *
 * @param adjudicationId   全局唯一裁决记录标识
 * @param submissionId     被裁决的提交记录标识
 * @param decision         裁决结果：ACCEPT
 * @param reason           裁决理由
 * @param operator         裁决操作者标识
 * @param adjudicatedAtUtc 裁决完成时刻（UTC，ISO-8601）
 * @param mergedVersion    裁决接受后合并产生的观测记录版本号
 * @param status           裁决后提交状态：MANUAL_ACCEPTED
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdjudicationResponse(
        String adjudicationId,
        String submissionId,
        String decision,
        String reason,
        String operator,
        Instant adjudicatedAtUtc,
        Integer mergedVersion,
        String status) {

    /**
     * 由裁决记录与裁决后提交状态构造响应。
     */
    public static AdjudicationResponse of(AdjudicationRecord record, String status) {
        return new AdjudicationResponse(record.adjudicationId(), record.submissionId(), record.decision(),
                record.reason(), record.operator(), record.adjudicatedAtUtc(), record.mergedVersion(), status);
    }
}
