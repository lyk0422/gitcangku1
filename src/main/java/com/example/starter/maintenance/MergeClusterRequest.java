package com.example.starter.maintenance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 冲突簇自动合并请求：按提交时刻固化的维护标记裁决胜出版本。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 */
public record MergeClusterRequest(
        @NotBlank @Size(max = 128) String requestId) {
}
