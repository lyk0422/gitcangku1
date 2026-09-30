package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 设备硬件画像写入/更新请求。首次提交 expectedVersion 传 0 或不传；
 * 之后更新须传当前画像版本号，版本不匹配返回 409。
 * model 或 memoryMb 显式传 null 表示画像缺少该字段，门禁按不兼容处理并给出对应原因。
 *
 * @param requestId      幂等请求ID
 * @param expectedVersion 期望的当前画像版本；null 或 0 表示首次提交
 * @param model          画像型号；null 表示缺少型号字段
 * @param memoryMb       画像内存，单位MB；null 表示缺少内存字段
 * @param tags           具备的能力标签；null 按空集合处理
 */
public record UpsertDeviceProfileRequest(
        @NotBlank @Size(max = 64) String requestId,
        @PositiveOrZero Integer expectedVersion,
        @Size(max = 64) String model,
        @PositiveOrZero Integer memoryMb,
        List<@NotBlank @Size(max = 64) String> tags) {

    public int expectedVersionOrZero() {
        return expectedVersion == null ? 0 : expectedVersion;
    }

    public List<String> effectiveTags() {
        return tags == null ? List.of() : tags;
    }
}
