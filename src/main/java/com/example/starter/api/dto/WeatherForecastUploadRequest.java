package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 上传预测气象请求：整批校验通过后一次性提交为一个新的气象版本，
 * 任一条目非法则整批不创建。历史预测版本保留不可变。
 *
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 * @param operator  操作者标识，计入幂等指纹
 * @param entries   本批预测条目（至少一条；同一区域同一时段不允许重复）
 */
public record WeatherForecastUploadRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotNull @Valid @Size(min = 1, max = 500) List<WeatherForecastEntryDto> entries) {
}