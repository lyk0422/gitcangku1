package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 证书链轮换请求：必须显式指定旧链版本与新链版本。旧开放链在新链生效时刻闭合，
 * 区间首尾相接不重叠；deviceIds 为轮换时刻授权信任新链的设备集合。
 */
public record RolloverCertChainRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String model,
        @NotBlank @Size(max = 64) String firmwareVersion,
        @NotNull Integer oldChainVersion,
        @NotNull Integer newChainVersion,
        @NotBlank @Size(max = 256) String certFingerprint,
        @NotBlank @Size(max = 40) String validFromUtc,
        @NotNull List<@NotBlank @Size(max = 64) String> deviceIds,
        @NotBlank @Size(max = 64) String operator) {
}
