package com.example.starter.artifact.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 依赖声明：闭区间 [minVersion, maxVersion]。
 *
 * @param name       依赖的制品名称
 * @param minVersion 最低版本（含），正整数
 * @param maxVersion 最高版本（含），正整数，须大于等于 minVersion
 */
public record DependencyDto(
        @NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*") String name,
        @Min(1) @Max(Integer.MAX_VALUE) int minVersion,
        @Min(1) @Max(Integer.MAX_VALUE) int maxVersion) {
}
