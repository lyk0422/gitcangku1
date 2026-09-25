package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 冲突簇人工裁决请求：指定胜出观测，裁决结论不被后续自动重算覆盖。
 *
 * @param requestId            全局唯一请求标识（幂等去重键）
 * @param winnerObservationId  人工选择的胜出观测标识（必须是该簇当前成员）
 * @param operator             操作者标识
 */
public record ResolveClusterRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String winnerObservationId,
        @NotBlank @Size(max = 128) String operator) {
}
