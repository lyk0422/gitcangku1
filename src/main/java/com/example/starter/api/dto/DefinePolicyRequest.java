package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 定义来源策略请求：为指定锁定图创建一个不可改写的新版本。
 *
 * @param lockfileName 锁定图名称
 * @param baseVersion  基于的当前策略版本（乐观并发）；首次定义传 0
 * @param coordinates  坐标要求，按名称升序持久化，名称不可重复，1～50 条
 */
public record DefinePolicyRequest(
        @NotBlank String lockfileName,
        @NotNull @PositiveOrZero Integer baseVersion,
        @Valid @Size(min = 1, max = 50) List<@Valid PolicyCoordinateSpec> coordinates) {

    public DefinePolicyRequest {
        if (coordinates == null) {
            coordinates = List.of();
        } else {
            coordinates = List.copyOf(coordinates);
        }
    }
}
