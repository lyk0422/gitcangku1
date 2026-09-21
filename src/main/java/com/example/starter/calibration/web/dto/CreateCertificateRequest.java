package com.example.starter.calibration.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 创建证书请求。时间须为带偏移的 ISO-8601 字符串；a、b 为最多 6 位小数的十进制字符串。
 */
public record CreateCertificateRequest(
        @NotBlank(message = "instrumentId 不能为空") String instrumentId,
        @NotBlank(message = "validFrom 不能为空") String validFrom,
        @NotBlank(message = "validTo 不能为空") String validTo,
        @NotBlank(message = "a 不能为空") String a,
        @NotBlank(message = "b 不能为空") String b) {
}
