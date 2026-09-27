package com.example.starter.firmware.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 证书链撤销请求。撤销时刻取服务端可控时钟（UTC），无需调用方传入。
 */
public record RevokeCertRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String model,
        @NotBlank @Size(max = 64) String firmwareVersion,
        @Min(1) int chainVersion,
        @NotBlank @Size(max = 64) String operator) {
}
