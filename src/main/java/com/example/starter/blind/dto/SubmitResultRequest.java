package com.example.starter.blind.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 提交/修订参与者结果请求体。
 *
 * @param value 整数结果值，0~100，无单位
 */
public record SubmitResultRequest(
        @NotNull(message = "value 不能为空")
        @Min(value = 0, message = "结果值必须在 0~100 之间")
        @Max(value = 100, message = "结果值必须在 0~100 之间")
        Integer value
) {
}
