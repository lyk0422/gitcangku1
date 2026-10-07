package com.example.starter.exposure.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 轮转池选择请求：为指定访客在池内做一次缺额轮转选择，池编号来自路径。
 *
 * @param requestId       写操作全局唯一幂等键
 * @param operator        操作者标识，非空
 * @param visitorId       合成访客编号
 * @param expectedVersion 期望的池配置版本；与当前版本不一致返回 409
 */
public record SelectExposureRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String visitorId,
        @NotNull @Min(1) Integer expectedVersion
) {
}
