package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 修改固件硬件兼容矩阵请求。
 * 空集合表示兼容全部硬件型号；集合换序视为同参（不产生新版本）；型号重复或未知返回 422。
 *
 * @param requestId       幂等请求ID
 * @param firmwareVersion 目标固件版本，必须与当前矩阵版本匹配 expectedVersion
 * @param expectedVersion 调用方持有的矩阵版本；首次配置传 0，后续传查询到的当前版本
 * @param models          允许的硬件型号集合；空列表表示兼容全部型号
 */
public record UpdateMatrixRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String firmwareVersion,
        @NotNull Integer expectedVersion,
        @NotNull List<@NotBlank @Size(max = 64) String> models) {
}
