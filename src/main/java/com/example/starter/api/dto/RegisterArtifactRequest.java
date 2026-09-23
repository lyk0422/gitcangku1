package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 制品登记请求：name 与正整数 version 联合唯一，依赖 0～10 条，平台 0～10 项。
 *
 * @param platforms 支持平台集合，元素为 {@code os/arch}，最多 10 项；
 *                  为空或仅含 ANY 表示全平台；旧请求不带该字段时按 ANY 处理
 */
public record RegisterArtifactRequest(
        @NotBlank String name,
        @Positive int version,
        List<@Valid DependencySpec> dependencies,
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

    /** 旧签名兼容：未声明平台时按 ANY（全平台）登记。 */
    public RegisterArtifactRequest(String name, int version, List<DependencySpec> dependencies) {
        this(name, version, dependencies, List.of());
    }
}
