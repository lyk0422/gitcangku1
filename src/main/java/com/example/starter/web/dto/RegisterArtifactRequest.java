package com.example.starter.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 制品版本登记请求。
 *
 * @param requestId    全局唯一写请求ID，用于幂等重放
 * @param name         制品名称
 * @param version      制品版本号，正整数
 * @param dependencies 依赖声明列表，0～10条，可省略
 */
public record RegisterArtifactRequest(
        @NotBlank String requestId,
        @NotBlank String name,
        @NotNull @Positive Integer version,
        @Valid @Size(max = 10) List<DependencyRequest> dependencies
) {
}
