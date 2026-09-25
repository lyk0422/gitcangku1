package com.example.starter.exposure.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 配置渠道日总量频控请求（创建或修改）。
 *
 * @param requestId       写操作全局唯一幂等键
 * @param dailyCap        每 UTC 日总确认额度，单位次
 * @param expectedVersion 期望的当前版本号：新建必须为 null；修改必须等于当前版本，否则 409
 */
public record UpsertChannelConfigRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull @Min(1) @Max(1_000_000) Integer dailyCap,
        @Min(1) Long expectedVersion
) {
}
