package com.example.starter.exposure.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 池轮转选择请求；池编号来自路径。
 *
 * @param requestId       写操作全局唯一幂等键
 * @param operator        操作者，非空
 * @param visitorId       合成访客编号
 * @param expectedVersion 期望池版本；非空且与当前版本不一致时返回 409，缺省不校验
 */
public record SelectFromPoolRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String visitorId,
        @Min(1) Integer expectedVersion
) {
}
