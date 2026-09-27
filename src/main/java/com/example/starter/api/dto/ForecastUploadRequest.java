package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 预测气象上传请求。整批在一个事务内写入并分配新的全局预测版本；
 * 任一区域不存在或预测值非法则整批不生效。
 *
 * @param entries   预测条目（1~50 条）
 * @param operator  操作者标识，参与幂等指纹
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 */
public record ForecastUploadRequest(
        @NotNull @Valid @Size(min = 1, max = 50) List<ForecastEntryDto> entries,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String requestId) {
}
