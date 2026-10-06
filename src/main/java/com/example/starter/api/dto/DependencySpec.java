package com.example.starter.api.dto;

import com.example.starter.domain.DependencyScope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 依赖声明：闭区间 [minimumVersion, maximumVersion]，版本均为正整数。
 *
 * @param scope 作用域：PUBLIC=当前作用域共享唯一版本；PRIVATE=目标进入子作用域。
 *              缺省（旧请求未携带）为 PUBLIC。
 */
public record DependencySpec(
        @NotBlank String name,
        @Positive int minimumVersion,
        @Positive int maximumVersion,
        DependencyScope scope) {

    public DependencySpec {
        if (scope == null) {
            scope = DependencyScope.PUBLIC;
        }
    }

    /** 兼容旧调用：未指定作用域时默认 PUBLIC。 */
    public DependencySpec(String name, int minimumVersion, int maximumVersion) {
        this(name, minimumVersion, maximumVersion, DependencyScope.PUBLIC);
    }
}
