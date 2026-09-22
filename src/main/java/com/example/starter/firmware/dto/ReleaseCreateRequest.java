package com.example.starter.firmware.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 发布单创建请求。
 *
 * @param requestId   全局唯一请求号，用于幂等去重
 * @param model       目标设备型号
 * @param fromVersion 来源固件版本
 * @param toVersion   目标固件版本，必须与来源版本不同
 * @param ratio       投放比例，取值 0~100
 */
public record ReleaseCreateRequest(
        @NotBlank String requestId,
        @NotBlank String model,
        @NotBlank String fromVersion,
        @NotBlank String toVersion,
        @NotNull @Min(0) @Max(100) Integer ratio) {
}
