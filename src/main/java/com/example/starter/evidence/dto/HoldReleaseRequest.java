package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 解除保留冻结请求。解除人必须为不同于创建人的保管人，并签明原因。
 *
 * @param commandKey 幂等命令键（同一解除 requestId）
 * @param reason     解除原因，非空
 */
public record HoldReleaseRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 512) String reason) {
}
