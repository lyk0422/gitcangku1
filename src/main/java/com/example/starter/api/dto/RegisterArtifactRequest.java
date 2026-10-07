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
 * @param sizeBytes 制品体积，单位字节，非负整数；缺省（旧客户端未声明）按 0 处理
 */
public record RegisterArtifactRequest(
        @NotBlank String name,
        @Positive int version,
        @PositiveOrZero Long sizeBytes,
        @Valid @Size(max = 10) List<@Valid DependencySpec> dependencies) {

    public RegisterArtifactRequest {
        if (dependencies == null) {
            dependencies = List.of();
        } else {
            dependencies = List.copyOf(dependencies);
        }
        if (sizeBytes == null) {
            sizeBytes = 0L;
        }
    }

    /** 兼容旧调用：未声明体积时按 0 处理。 */
    public RegisterArtifactRequest(String name, int version, List<DependencySpec> dependencies) {
        this(name, version, 0L, dependencies);
    }
}
