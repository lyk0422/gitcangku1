package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 冲突簇人工裁决请求：选定簇内一条观测记录作为胜出记录，裁决结论不被自动覆盖。
 *
 * @param requestId     全局唯一请求标识（幂等去重键）
 * @param observationId 选定的胜出观测记录标识（必须是簇成员）
 * @param operator      操作者标识
 */
public record ResolveClusterRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String observationId,
        @NotBlank @Size(max = 128) String operator) {
}
