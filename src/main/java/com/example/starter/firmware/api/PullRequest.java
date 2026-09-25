package com.example.starter.firmware.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 设备拉取请求：可携带紧急例外以在冻结窗口内放行。
 */
public record PullRequest(
        @NotBlank @Size(max = 64) String requestId,
        @Valid EmergencyException exception) {
}
