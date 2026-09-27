package com.example.starter.maintenance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 冲突簇合并请求：对簇内观测执行自动合并，全簇受维护影响时结果为待人工裁决。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 */
public record MergeClusterRequest(
        @NotBlank @Size(max = 128) String requestId) {
}
