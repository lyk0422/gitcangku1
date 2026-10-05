package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 多根联合锁定请求：2 至 4 个不同名称的精确根版本与期望的仓库版本号。
 * 根集合换序视为同一请求（幂等身份按名称排序后计算）。
 */
public record JointLockRequest(
        @NotNull @Size(min = 2, max = 4) List<@Valid RootSpec> roots,
        @NotNull @PositiveOrZero Long expectedRepositoryVersion) {

    public JointLockRequest {
        if (roots != null) {
            roots = List.copyOf(roots);
        }
    }
}
