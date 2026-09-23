package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 制品登记请求：name 与正整数 version 联合唯一，依赖 0～10 条。
 *
 * @param contentDigest 可选的制品内容摘要（64 位十六进制小写 SHA-256）；
 *                      未提供时服务端按规范化坐标与依赖派生稳定摘要
 */
public record RegisterArtifactRequest(
        @NotBlank String name,
        @Positive int version,
        @Pattern(regexp = "[0-9a-f]{64}", message = "contentDigest 必须为 64 位十六进制小写 SHA-256")
        String contentDigest,
        @Valid @Size(max = 10) List<@Valid DependencySpec> dependencies) {

    public RegisterArtifactRequest {
        if (dependencies == null) {
            dependencies = List.of();
        } else {
            dependencies = List.copyOf(dependencies);
        }
    }
}
