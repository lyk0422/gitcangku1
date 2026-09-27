package com.example.starter.maintenance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 人工裁决请求：可选择维护受影响观测，但必须写入理由和裁决人。
 *
 * @param requestId          全局唯一请求标识（幂等去重键）
 * @param adjudicationId     全局唯一裁决记录标识
 * @param chosenSubmissionId 人工选择的胜出提交标识（必须属于该冲突簇，可为维护受影响观测）
 * @param reason             裁决理由（必填）
 * @param adjudicator        裁决人标识（必填）
 */
public record AdjudicateRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String adjudicationId,
        @NotBlank @Size(max = 128) String chosenSubmissionId,
        @NotBlank @Size(max = 1024) String reason,
        @NotBlank @Size(max = 128) String adjudicator) {
}
