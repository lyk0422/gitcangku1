package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 制品登记请求：name 与正整数 version 联合唯一，依赖 0～10 条。
 *
 * @param digest 可选的构建摘要（64 位十六进制 SHA-256，大小写不敏感，存储为小写）；
 *               为空表示未登记摘要，来源策略校验时不参与摘要比对
 */
public record RegisterArtifactRequest(
        @NotBlank String name,
        @Positive int version,
        @Valid @Size(max = 10) List<@Valid DependencySpec> dependencies,
        String digest) {

    public RegisterArtifactRequest {
        if (dependencies == null) {
            dependencies = List.of();
        } else {
            dependencies = List.copyOf(dependencies);
        }
        if (digest != null) {
            digest = digest.trim().toLowerCase(java.util.Locale.ROOT);
            if (digest.isEmpty()) {
                digest = null;
            }
        }
    }

    /** 兼容构造：不声明构建摘要。 */
    public RegisterArtifactRequest(String name, int version, List<DependencySpec> dependencies) {
        this(name, version, dependencies, null);
    }
}
