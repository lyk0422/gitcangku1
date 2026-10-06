package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 制品登记请求：name 与正整数 version 联合唯一，依赖 0～10 条。
 *
 * @param expectedRepositoryVersion 期望的仓库版本号；为 null 表示不校验，
 *                                  非 null 时与当前仓库版本不一致返回 409
 */
public record RegisterArtifactRequest(
        @NotBlank String name,
        @Positive int version,
        @Valid @Size(max = 10) List<@Valid DependencySpec> dependencies,
        @PositiveOrZero Long expectedRepositoryVersion) {

    /** 兼容旧调用：不携带期望仓库版本号。 */
    public RegisterArtifactRequest(String name, int version, List<DependencySpec> dependencies) {
        this(name, version, dependencies, null);
    }

    public RegisterArtifactRequest {
        if (dependencies == null) {
            dependencies = List.of();
        } else {
            dependencies = List.copyOf(dependencies);
        }
    }
}
