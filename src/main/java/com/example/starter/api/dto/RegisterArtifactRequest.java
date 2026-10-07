package com.example.starter.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 制品登记请求：name 与正整数 version 联合唯一，依赖 0～10 条。
 *
 * @param sizeBytes 制品体积（字节），非负整数；为 null 时按 0 处理，兼容旧客户端未声明
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
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
    }

    /** 旧客户端三参构造：未声明体积按 0 兼容。 */
    public RegisterArtifactRequest(String name, int version, List<DependencySpec> dependencies) {
        this(name, version, null, dependencies);
    }

    /** 登记时实际使用的体积：未声明按 0。 */
    public long effectiveSizeBytes() {
        return sizeBytes == null ? 0L : sizeBytes;
    }
}
