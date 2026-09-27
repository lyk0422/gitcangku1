package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 人工裁决请求：接受被围栏拒绝的观测提交，必须填写理由。
 *
 * @param requestId      全局唯一请求标识（幂等去重键）
 * @param adjudicationId 全局唯一裁决记录标识
 * @param decision       裁决结果：仅支持 ACCEPT（接受被拒观测）
 * @param reason         裁决理由（必填，非空）
 * @param operator       裁决操作者标识
 */
public record AdjudicateSubmissionRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String adjudicationId,
        @NotBlank @Size(max = 16) String decision,
        @NotBlank @Size(max = 1024) String reason,
        @NotBlank @Size(max = 128) String operator) {
}
