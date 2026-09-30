package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 声明固件版本硬件兼容要求请求。
 *
 * @param requestId      幂等请求ID
 * @param firmwareVersion 固件目标版本号
 * @param minMemoryMb    最低运行内存，单位MB（1GB=1024MB）；null 表示不限制内存
 * @param models         允许投放的设备型号集合，画像型号须命中其一，不能为空
 * @param tags           必需能力标签集合，画像须包含全部；null 或空表示不要求标签
 */
public record DeclareHardwareRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String firmwareVersion,
        @PositiveOrZero Integer minMemoryMb,
        @NotEmpty List<@NotBlank @Size(max = 64) String> models,
        List<@NotBlank @Size(max = 64) String> tags) {

    public List<String> effectiveTags() {
        return tags == null ? List.of() : tags;
    }
}
