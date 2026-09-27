package com.example.starter.observation.geofence;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 人工裁决请求：接受一条被围栏拒绝的观测，必须填写理由。
 *
 * @param requestId      全局唯一请求标识（幂等去重键）
 * @param adjudicationId 全局唯一裁决记录标识
 * @param operator       裁决操作者标识
 * @param reason         裁决理由，必填（空白返回 422）
 */
public record AdjudicateRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String adjudicationId,
        @NotBlank @Size(max = 128) String operator,
        @Size(max = 1024) String reason) {
}
