package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 人工裁决请求：对围栏拒绝的离线观测执行人工接受，必须填写理由。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 * @param operator  裁决操作者标识
 * @param decision  裁决结果，仅允许 ACCEPT
 * @param reason    人工裁决理由（必填，非空白）
 */
public record AdjudicateSubmissionRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String operator,
        @NotBlank String decision,
        @NotBlank @Size(max = 1024) String reason) {
}
