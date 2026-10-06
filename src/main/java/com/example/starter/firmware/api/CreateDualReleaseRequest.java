package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建双分区发布单请求。operator 为操作者标识，计入幂等指纹。
 */
public record CreateDualReleaseRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String model,
        @NotBlank @Size(max = 64) String targetVersion) {
}
