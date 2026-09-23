package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 依赖声明：闭区间 [minimumVersion, maximumVersion]，版本均为正整数。
 *
 * @param optional 是否可选依赖；为 null 时按 false（必选）处理以兼容旧请求
 */
public record DependencySpec(
        @NotBlank String name,
        @Positive int minimumVersion,
        @Positive int maximumVersion,
        Boolean optional) {

    /** 兼容旧请求：构造必选依赖。 */
    public DependencySpec(String name, int minimumVersion, int maximumVersion) {
        this(name, minimumVersion, maximumVersion, false);
    }

    public boolean optionalFlag() {
        return optional != null && optional;
    }
}
