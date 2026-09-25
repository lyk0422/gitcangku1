package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 解除隔离请求：必须由与最近一次隔离提交人不同的运维确认原因已消除。
 *
 * @param requestId       全局唯一请求ID（isolationKey）
 * @param operator        确认解除的运维工号，不得等于隔离提交人
 * @param reasonCode      原因已消除的确认代码
 * @param expectedVersion 提交时设备当前固件版本
 */
public record ReleaseQuarantineRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String reasonCode,
        @NotBlank @Size(max = 64) String expectedVersion) {
}
