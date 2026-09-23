package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 依赖声明：闭区间 [minimumVersion, maximumVersion]，版本均为正整数。
 *
 * @param optional 是否可选依赖；旧请求不带该字段时由紧凑构造器补为 false
 */
public record DependencySpec(
        @NotBlank String name,
        @Positive int minimumVersion,
        @Positive int maximumVersion,
        Boolean optional) {

    public DependencySpec {
        // 兼容旧请求：未传 optional 时按必选依赖处理。
        if (optional == null) {
            optional = false;
        }
    }

    /** 旧签名兼容：默认登记为必选依赖。 */
    public DependencySpec(String name, int minimumVersion, int maximumVersion) {
        this(name, minimumVersion, maximumVersion, false);
    }
}
