package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 上传预测气象请求：条目整体固化为一个新预测版本（版本由系统递增分配）。
 * 引用未知区域、负值或重复条目返回 422。
 *
 * @param entries   预测条目（至少一个）
 * @param operator  操作者标识，参与幂等指纹计算
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 */
public record ForecastUploadRequest(
        @NotNull @Valid @Size(min = 1, max = 500) List<ForecastEntryDto> entries,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String requestId) {
}