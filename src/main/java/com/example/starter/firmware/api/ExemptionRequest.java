package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 设备组模块豁免配置/修订请求。必须显式列出豁免模块与到期时刻：
 * modules 非空且元素不重复；expiresAtUtc 为 UTC ISO-8601 时刻，必须晚于当前时刻，到期自动失效。
 */
public record ExemptionRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String groupName,
        @NotEmpty List<@NotBlank @Size(max = 64) String> modules,
        @NotBlank @Size(max = 40) String expiresAtUtc) {
}
