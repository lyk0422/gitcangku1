package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 提交测量向量请求。values 必须恰好覆盖批次绑定规范版本的全部特性，
 * 每值最多 6 位小数；缺特性、多特性或超精度返回 400 且不留检验记录。
 * expectedVersion 必须等于批次当前绑定版本，否则 409。
 */
public record SubmitMeasurementRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "operator 不能为空") String operator,
        @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion,
        @NotNull(message = "values 不能为空") Map<String, @NotNull(message = "测量值不能为空") BigDecimal> values
) {
}
