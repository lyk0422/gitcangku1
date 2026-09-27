package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 冲突簇人工裁决请求：对 PENDING_MANUAL 的簇合并选择胜出提交（可为受影响观测），必须写入理由和裁决人。
 *
 * @param requestId          全局唯一请求标识（幂等去重键）
 * @param mergeId            待裁决簇合并记录标识（原合并请求 requestId）
 * @param winningSubmissionId 裁决胜出的提交标识，必须属于该冲突簇
 * @param adjudicator        裁决人标识（必填）
 * @param reason             裁决理由（必填）
 */
public record ManualDecisionRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String mergeId,
        @NotBlank @Size(max = 128) String winningSubmissionId,
        @NotBlank @Size(max = 128) String adjudicator,
        @NotBlank @Size(max = 1024) String reason) {
}
