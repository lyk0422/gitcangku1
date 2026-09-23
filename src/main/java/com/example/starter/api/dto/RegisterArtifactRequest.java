package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 制品登记请求：name 与正整数 version 联合唯一，依赖 0～10 条，平台 0～10 项。
 *
 * @param platforms 支持平台集合，元素为 os/arch 或仅含 ANY；缺省或 null 视为 ANY
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
        // null 与缺省均表示 ANY；空列表由领域校验拒绝（ANY 需显式表达）。
        if (platforms == null) {
            platforms = List.of("ANY");
        } else {
            platforms = List.copyOf(platforms);
        }
    }

    /** 旧请求便捷构造器：不区分平台（ANY）。 */
    public RegisterArtifactRequest(String name, int version, List<DependencySpec> dependencies) {
        this(name, version, dependencies, List.of("ANY"));
    }
}
