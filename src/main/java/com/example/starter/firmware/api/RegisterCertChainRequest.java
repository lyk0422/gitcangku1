package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 首个签名证书链登记请求。chainVersion 必须为 1；区间为闭开区间 [validFromUtc, validToUtc)，
 * validToUtc 传 null 表示开放区间。deviceIds 为创建时刻即信任该链的设备集合（须已登记）。
 */
public record RegisterCertChainRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String model,
        @NotBlank @Size(max = 64) String firmwareVersion,
        @NotNull Integer chainVersion,
        @Size(max = 256) String certFingerprint,
        @NotBlank @Size(max = 40) String validFromUtc,
        @Size(max = 40) String validToUtc,
        @NotNull List<@NotBlank @Size(max = 64) String> deviceIds,
        @NotBlank @Size(max = 64) String operator) {
}
