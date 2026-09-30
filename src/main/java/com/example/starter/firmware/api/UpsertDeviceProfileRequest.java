package com.example.starter.firmware.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 设备画像上报/更新请求。model、memoryMb、tags 均可缺省（null），
 * 表示设备缺少该画像字段，投放筛选时按不兼容处理。每次成功更新画像版本加一。
 */
public record UpsertDeviceProfileRequest(
        @NotBlank @Size(max = 64) String requestId,
        @Size(max = 64) String model,
        @Min(0) Integer memoryMb,
        List<@NotBlank @Size(max = 64) String> tags) {
}
