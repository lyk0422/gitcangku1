package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 制品登记请求：name 与正整数 version 联合唯一；普通依赖 0～10 条，peer 依赖 0～3 条。
 *
 * @param operator 执行本次写入的非空操作者
 * @param peers    peer 依赖声明，不建立可达边；peer 名称不能与本版本自身同名
 */
public record RegisterArtifactRequest(
        @NotBlank String name,
        @Positive int version,
        @NotBlank String operator,
        @Valid @Size(max = 10) List<@Valid DependencySpec> dependencies,
        @Valid @Size(max = 3) List<@Valid PeerSpec> peers) {

    public RegisterArtifactRequest {
        if (dependencies == null) {
            dependencies = List.of();
        } else {
            dependencies = List.copyOf(dependencies);
        }
        if (peers == null) {
            peers = List.of();
        } else {
            peers = List.copyOf(peers);
        }
    }
}
