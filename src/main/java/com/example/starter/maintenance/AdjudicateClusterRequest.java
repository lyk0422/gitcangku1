package com.example.starter.maintenance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 冲突簇人工裁决请求：允许选择受维护影响的观测，但必须写入裁决理由与裁决人。
 *
 * @param requestId      全局唯一请求标识（幂等去重键）
 * @param adjudicationId 全局唯一裁决记录标识
 * @param observationId  裁决选中的观测标识（须属于该冲突簇最新待裁决快照）
 * @param reason         裁决理由（不允许为空）
 * @param adjudicator    裁决人标识（不允许为空）
 */
public record AdjudicateClusterRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String adjudicationId,
        @NotBlank @Size(max = 64) String observationId,
        @NotBlank @Size(max = 1024) String reason,
        @NotBlank @Size(max = 128) String adjudicator) {
}
