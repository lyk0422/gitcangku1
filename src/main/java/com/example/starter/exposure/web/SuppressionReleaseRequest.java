package com.example.starter.exposure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 解除访客抑制名单请求；访客编号来自路径。
 *
 * @param requestId 写操作全局唯一幂等键
 */
public record SuppressionReleaseRequest(
        @NotBlank @Size(max = 64) String requestId
) {
}
