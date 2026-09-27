package com.example.starter.firmware.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 发布单模块豁免修改请求：整体替换当前豁免集合，豁免版本加一，历史版本保留。
 * 每条豁免必须显式列出模块与到期时刻（UTC，ISO-8601）；有效期左闭右开，到期自动失效。
 */
public record ExemptionUpdateRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull @Valid List<ExemptionEntry> exemptions) {

    /**
     * 单条豁免：指定设备组的指定模块在到期时刻前免于模块兼容校验。
     */
    public record ExemptionEntry(
            @NotBlank @Size(max = 64) String deviceGroup,
            @NotEmpty List<@NotBlank @Size(max = 64) String> modules,
            @NotBlank @Size(max = 40) String expiresAtUtc) {
    }
}
