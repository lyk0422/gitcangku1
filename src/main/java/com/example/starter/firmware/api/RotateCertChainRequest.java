package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 证书链轮换请求：必须指定旧链版本与新链版本。新链生效时刻即旧链失效时刻（旧链区间被截断），
 * 新链区间不得与其他链重叠。deviceSet 缺省或空表示全部设备信任。
 */
public record RotateCertChainRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String oldChainVersion,
        @NotBlank @Size(max = 64) String newChainVersion,
        @NotBlank @Size(max = 40) String validFromUtc,
        @Size(max = 40) String validToUtc,
        List<@NotBlank @Size(max = 64) String> deviceSet,
        @NotBlank @Size(max = 64) String operator) {
}
