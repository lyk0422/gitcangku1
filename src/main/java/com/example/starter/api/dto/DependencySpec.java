package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 依赖声明：闭区间 [minimumVersion, maximumVersion]，版本均为正整数。
 *
 * @param exclusions 边级路径排除名称（最多 3 个，不区分顺序）：
 *                   经过该边之后的子图中忽略同名边，不能移除该边目标本身
 */
public record DependencySpec(
        @NotBlank String name,
        @Positive int minimumVersion,
        @Positive int maximumVersion,
        @Size(max = 3) List<@NotBlank String> exclusions) {

    public DependencySpec {
        if (exclusions == null) {
            exclusions = List.of();
        } else {
            exclusions = List.copyOf(exclusions);
        }
    }

    /** 无排除的便捷构造。 */
    public DependencySpec(String name, int minimumVersion, int maximumVersion) {
        this(name, minimumVersion, maximumVersion, List.of());
    }
}
