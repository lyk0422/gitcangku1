package com.example.starter.firmware.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 证书轮换请求：必须同时指定旧链版本与新链版本（新链版本必须为该固件下一序号）。
 * effectiveAtUtc 为新链生效时刻（UTC），旧开放区间在同一时刻截断（半开区间不重叠）。
 * deviceIds 为新链授权设备集合，留空表示全开放。
 */
public record RolloverCertRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String model,
        @NotBlank @Size(max = 64) String firmwareVersion,
        @Min(1) int oldChainVersion,
        @Min(1) int newChainVersion,
        @NotBlank @Size(max = 40) String effectiveAtUtc,
        @NotBlank @Size(max = 256) String subjectDn,
        @NotBlank @Size(max = 256) String issuerDn,
        @NotBlank @Size(max = 96) String fingerprintSha256,
        @Size(max = 1000) List<@NotBlank @Size(max = 64) String> deviceIds,
        @NotBlank @Size(max = 64) String operator) {
}
