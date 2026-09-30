package com.example.starter.exposure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 访客加入抑制名单请求。
 *
 * @param requestId 写操作全局唯一幂等键
 * @param visitorId 合成访客编号
 */
public record SuppressionJoinRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String visitorId
) {
}
