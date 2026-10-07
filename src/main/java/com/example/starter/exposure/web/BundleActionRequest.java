package com.example.starter.exposure.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 组合确认/取消请求体；组编号来自路径。
 *
 * @param requestId       写操作全局唯一幂等键
 * @param operator        操作者标识，非空
 * @param expectedVersion 期望的组版本（乐观锁），与当前版本不一致时 409
 */
public record BundleActionRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotNull @Min(0) Long expectedVersion
) {
}
