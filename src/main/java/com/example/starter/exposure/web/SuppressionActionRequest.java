package com.example.starter.exposure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 加入/解除访客抑制名单请求体，仅携带幂等键；访客编号来自路径。
 *
 * @param requestId 名单变更全局唯一幂等键；同键重放首次结果，换访客或动作返回 409
 */
public record SuppressionActionRequest(
        @NotBlank @Size(max = 64) String requestId
) {
}
