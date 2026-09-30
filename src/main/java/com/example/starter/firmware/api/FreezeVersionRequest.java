package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 版本正式发布冻结请求：冻结目标固件版本，记录创建人、原因与 UTC 生效时刻。
 */
public record FreezeVersionRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String version,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 256) String reason) {
}
