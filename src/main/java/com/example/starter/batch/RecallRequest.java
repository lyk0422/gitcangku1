package com.example.starter.batch;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 召回请求。仅已放行（RELEASED）批次可召回，操作人通过 X-Actor-Id 请求头提供。
 *
 * @param commandKey 命令幂等键
 * @param reason     召回原因，非空
 */
public record RecallRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 512) String reason) {
}
