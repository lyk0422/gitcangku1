package com.example.starter.permit;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 冲突簇人工裁决请求：在已合并的冲突簇中选定唯一胜出观测；已裁决簇重放/改参按幂等与冲突规则处理。
 *
 * @param requestId            全局唯一请求标识（幂等去重键）
 * @param chosenObservationKey 裁决选定的观测业务键，必须属于该簇
 * @param operator             操作者标识
 */
public record ResolveClusterRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String chosenObservationKey,
        @NotBlank @Size(max = 128) String operator) {
}
