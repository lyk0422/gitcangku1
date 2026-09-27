package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 撤销证书链请求。撤销后该链不再作为有效链；已冻结该链的任务回执转入人工复核。
 */
public record RevokeCertChainRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator) {
}
