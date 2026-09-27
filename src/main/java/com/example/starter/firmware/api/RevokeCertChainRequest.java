package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 证书链撤销请求。撤销后新拉取不得使用该链；已固化该链的在途任务仍可回执，但须人工复核。
 */
public record RevokeCertChainRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String model,
        @NotBlank @Size(max = 64) String firmwareVersion,
        @NotNull Integer chainVersion,
        @NotBlank @Size(max = 256) String reason,
        @NotBlank @Size(max = 64) String operator) {
}
