package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 豁免修改中的一条目：设备组 + 模块 + 到期时刻，必须逐模块显式列出，不支持通配。
 * expiresAtUtc 为 UTC ISO-8601；时间左闭右开，到达该时刻自动失效。
 */
public record ExemptionEntry(
        @NotBlank @Size(max = 64) String deviceGroup,
        @NotBlank @Size(max = 64) String moduleName,
        @NotBlank @Size(max = 40) String expiresAtUtc) {
}
