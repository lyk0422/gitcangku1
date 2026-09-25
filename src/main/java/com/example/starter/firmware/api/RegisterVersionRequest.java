package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 固件版本登记请求。predecessorVersion 为可选，缺省表示版本链起点。
 */
public record RegisterVersionRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String version,
        @Size(max = 64) String predecessorVersion) {
}
