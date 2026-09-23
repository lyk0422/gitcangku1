package com.example.starter.evidence.aliquot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 母样首次参与联合取样时的登记请求。总量为不可修改的正整数，单位不可修改。
 *
 * @param commandKey 幂等命令键
 * @param totalQty   母样总量，正整数，登记后不可修改
 * @param unit       数量单位，登记后不可修改
 */
public record MotherRegisterRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotNull @Positive Long totalQty,
        @NotBlank @Size(max = 32) String unit) {
}
