package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 依赖声明：闭区间 [minimumVersion, maximumVersion]，版本均为正整数。
 *
 * <p>exclusions 为边级排除名称（最多 3 个、不可重复）：经过该边之后，
 * 在其整个子图中忽略以这些名称为目标的边；排除不能移除该边目标本身。
 */
public record DependencySpec(
        @NotBlank String name,
        @Positive int minimumVersion,
        @Positive int maximumVersion,
        @Size(max = 3) List<@NotBlank String> exclusions) {

    public DependencySpec {
        exclusions = exclusions == null ? List.of() : List.copyOf(exclusions);
    }

    /** 无排除的依赖声明。 */
    public DependencySpec(String name, int minimumVersion, int maximumVersion) {
        this(name, minimumVersion, maximumVersion, List.of());
    }
}
