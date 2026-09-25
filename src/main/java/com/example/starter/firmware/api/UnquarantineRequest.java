package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 解除隔离请求：操作人必须与隔离人不同（双人确认原因已消除），
 * expectedVersion 必须等于设备当前固件版本。幂等指纹含操作者、设备版本、操作与原因。
 */
public record UnquarantineRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String reason,
        @NotBlank @Size(max = 64) String expectedVersion) {
}
