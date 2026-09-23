package com.example.starter.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 依赖声明：闭区间 [minimumVersion, maximumVersion]，版本均为正整数。
 *
 * @param optional 是否可选依赖；缺省 false 以兼容旧请求
 */
public record DependencySpec(
        @NotBlank String name,
        @Positive int minimumVersion,
        @Positive int maximumVersion,
        @JsonProperty(defaultValue = "false") boolean optional) {

    /** 旧请求便捷构造器：默认必选依赖。 */
    public DependencySpec(String name, int minimumVersion, int maximumVersion) {
        this(name, minimumVersion, maximumVersion, false);
    }
}
