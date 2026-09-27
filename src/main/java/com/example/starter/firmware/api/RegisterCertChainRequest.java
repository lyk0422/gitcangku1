package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 登记证书链请求。validFromUtc/validToUtc 为 UTC ISO-8601 时刻，有效区间为半开区间
 * [validFromUtc, validToUtc)，validToUtc 缺省表示长期有效；deviceSet 缺省或空表示全部设备信任。
 */
public record RegisterCertChainRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String chainVersion,
        @NotBlank @Size(max = 40) String validFromUtc,
        @Size(max = 40) String validToUtc,
        List<@NotBlank @Size(max = 64) String> deviceSet,
        @NotBlank @Size(max = 64) String operator) {
}
