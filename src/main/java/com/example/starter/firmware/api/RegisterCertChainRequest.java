package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 登记签名证书链请求。validFromUtc/validToUtc 为 UTC ISO-8601 时刻；
 * validToUtc 留空表示开放区间。deviceIds 为链授权设备集合，留空表示对适用设备全开放。
 */
public record RegisterCertChainRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String model,
        @NotBlank @Size(max = 64) String firmwareVersion,
        @NotBlank @Size(max = 256) String subjectDn,
        @NotBlank @Size(max = 256) String issuerDn,
        @NotBlank @Size(max = 96) String fingerprintSha256,
        @NotBlank @Size(max = 40) String validFromUtc,
        @Size(max = 40) String validToUtc,
        @Size(max = 1000) List<@NotBlank @Size(max = 64) String> deviceIds,
        @NotBlank @Size(max = 64) String operator) {
}
