package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 多根联合锁定请求：2～4 个不同名称的精确根版本与期望的仓库版本号。
 * 根集合与顺序无关，同名根（无论版本是否相同）视为重复，返回 400。
 */
public record MultiLockRequest(
        @NotNull @Size(min = 2, max = 4) List<@Valid MultiLockRoot> roots,
        @NotNull @PositiveOrZero Long expectedRepositoryVersion) {

    public MultiLockRequest {
        if (roots != null) {
            roots = List.copyOf(roots);
        }
    }
}
