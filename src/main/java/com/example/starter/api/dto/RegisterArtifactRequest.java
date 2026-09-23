package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 制品登记请求：name 与正整数 version 联合唯一，依赖 0～10 条。
 *
 * @param platforms 支持的目标平台集合（os/arch），最多 10 项；
 *                  为空时按 ANY 处理；仅含 ANY 表示平台无关。为 null 兼容旧请求
 */
public record RegisterArtifactRequest(
        @NotBlank String name,
        @Positive int version,
        @Valid @Size(max = 10) List<@Valid DependencySpec> dependencies,
        @Size(max = 10) List<String> platforms) {

    public RegisterArtifactRequest {
        if (dependencies == null) {
            dependencies = List.of();
        } else {
            dependencies = List.copyOf(dependencies);
        }
        if (platforms == null) {
            platforms = List.of();
        } else {
            platforms = List.copyOf(platforms);
        }
    }

    /** 兼容旧请求：不携带平台信息（按 ANY 处理）。 */
    public RegisterArtifactRequest(String name, int version, List<@Valid DependencySpec> dependencies) {
        this(name, version, dependencies, List.of());
    }
}
