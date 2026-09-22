package com.example.starter.artifact.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 制品登记请求。name + version 联合唯一，创建后依赖不可改。
 *
 * @param requestId    全局唯一幂等请求 id
 * @param name         制品名称
 * @param version      制品版本，正整数
 * @param dependencies 依赖声明，0~10 条，名称唯一；null 视为空
 */
public record RegisterArtifactRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*") String name,
        @Min(1) int version,
        @Size(max = 10) List<@Valid DependencyDto> dependencies) {
}
