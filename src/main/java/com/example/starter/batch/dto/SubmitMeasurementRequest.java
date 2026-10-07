package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 提交测量向量请求。values 必须一次性完整覆盖绑定规范版本的全部特性（顺序对应），
 * 各值最多 6 位小数；缺特性或超精度返回 400 且不留检验记录。
 */
public record SubmitMeasurementRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "operator 不能为空") String operator,
        @NotBlank(message = "measurementKey 不能为空") String measurementKey,
        @NotNull(message = "values 不能为空")
        @Size(min = 2, max = 5, message = "values 必须包含 2～5 个测量值")
        List<@NotNull(message = "测量值不能为空") BigDecimal> values
) {
}
