package com.example.starter.aliquot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 母样总量登记请求。母样首次参与联合取样前登记一次，总量与单位之后不可修改。
 *
 * @param commandKey    幂等命令键
 * @param totalQuantity 登记总量，正整数
 * @param unit          计量单位
 */
public record SampleRegisterRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @Positive Long totalQuantity,
        @NotBlank @Size(max = 32) String unit) {
}
