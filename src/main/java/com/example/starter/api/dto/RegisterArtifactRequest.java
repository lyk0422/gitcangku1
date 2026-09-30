package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 制品登记请求：name 与正整数 version 联合唯一，依赖 0～10 条。
 *
 * @param riskLevel 可选的该制品版本风险级别（NONE/LOW/MEDIUM/HIGH/CRITICAL）；为空按 NONE
 */
public record RegisterArtifactRequest(
        @NotBlank String name,
        @Positive int version,
        String riskLevel,
        @Valid @Size(max = 10) List<@Valid DependencySpec> dependencies) {

    public RegisterArtifactRequest {
        if (dependencies == null) {
            dependencies = List.of();
        } else {
            dependencies = List.copyOf(dependencies);
        }
    }

    /** 不指定风险级别（按 NONE）的登记请求。 */
    public RegisterArtifactRequest(String name, int version, List<DependencySpec> dependencies) {
        this(name, version, null, dependencies);
    }
}
