package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 制品登记请求：name 与正整数 version 联合唯一，依赖 0～10 条。
 *
 * @param contentDigest 可选的制品内容 SHA-256 摘要（64 位十六进制）；
 *                      缺省时服务端按规范化制品清单（名称/版本/依赖区间）冻结摘要，
 *                      后续追加签名的 digest 必须等于该冻结值
 */
public record RegisterArtifactRequest(
        @NotBlank String name,
        @Positive int version,
        String contentDigest,
        @Valid @Size(max = 10) List<@Valid DependencySpec> dependencies) {

    public RegisterArtifactRequest {
        if (dependencies == null) {
            dependencies = List.of();
        } else {
            dependencies = List.copyOf(dependencies);
        }
    }

    /** 兼容入口：不显式提供内容摘要，由服务端按规范化制品清单冻结。 */
    public RegisterArtifactRequest(String name, int version, List<DependencySpec> dependencies) {
        this(name, version, null, dependencies);
    }
}
