package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 制品登记请求：name 与正整数 version 联合唯一，依赖 0～10 条；
 * platforms 为该版本的可用平台白名单，省略或为空表示全平台可用。
 */
public record RegisterArtifactRequest(
        @NotBlank String name,
        @Positive int version,
        @Valid @Size(max = 10) List<@Valid DependencySpec> dependencies,
        @Size(max = 10) List<@NotBlank String> platforms) {

    public RegisterArtifactRequest {
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        platforms = platforms == null ? List.of() : List.copyOf(platforms);
    }

    /** 兼容入口：不提供平台白名单，表示该版本在所有平台可用。 */
    public RegisterArtifactRequest(String name, int version, List<DependencySpec> dependencies) {
        this(name, version, dependencies, List.of());
    }
}
