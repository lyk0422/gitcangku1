package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 依赖声明：闭区间 [minimumVersion, maximumVersion]，版本均为正整数。
 *
 * @param scope 依赖作用域：PUBLIC 或 PRIVATE（大小写不敏感），缺省为 PUBLIC
 */
public record DependencySpec(
        @NotBlank String name,
        @Positive int minimumVersion,
        @Positive int maximumVersion,
        String scope) {

    /** 兼容旧调用：未声明作用域时默认 PUBLIC。 */
    public DependencySpec(String name, int minimumVersion, int maximumVersion) {
        this(name, minimumVersion, maximumVersion, null);
    }
}
