package com.example.starter.firmware.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 设备登记请求。
 *
 * @param requestId       全局唯一请求号，用于幂等去重
 * @param deviceId        设备唯一标识
 * @param model           设备型号，登记后不可修改
 * @param firmwareVersion 当前固件版本
 * @param bucket          灰度分桶号，取值 0~99，登记后不可修改
 */
public record DeviceRegisterRequest(
        @NotBlank String requestId,
        @NotBlank String deviceId,
        @NotBlank String model,
        @NotBlank String firmwareVersion,
        @NotNull @Min(0) @Max(99) Integer bucket) {
}
