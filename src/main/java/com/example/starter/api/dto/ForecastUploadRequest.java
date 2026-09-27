package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 预测气象上传请求。每次上传生成一个新的预测版本，版本内记录不可变。
 * 任一条目区域不存在、时段退化、数值为负或同区域时段重叠都返回 422/404，
 * 整批不写入。
 *
 * @param entries   预测条目集合（至少一条）
 * @param operator  操作者标识，参与幂等指纹
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 */
public record ForecastUploadRequest(
        @NotNull @Valid @Size(min = 1, max = 500) List<ForecastEntryDto> entries,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String requestId) {
}
