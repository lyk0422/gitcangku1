package com.example.starter.observation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 联合裁决的观测版本前提：每条簇内观测都必须提交提交时预期的当前版本号。
 *
 * @param observationId   簇内观测标识
 * @param expectedVersion 提交方预期的当前版本号，不匹配返回 409
 */
public record BundleExpectedVersion(
        @NotBlank String observationId,
        @NotNull @Min(1) Integer expectedVersion) {
}
