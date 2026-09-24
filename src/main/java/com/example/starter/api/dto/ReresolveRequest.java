package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 锁文件重解析请求：业务幂等键 reresolveKey 与期望的仓库版本号。
 * 锁文件标识由路径参数给出。
 */
public record ReresolveRequest(
        @NotBlank String reresolveKey,
        @NotNull @PositiveOrZero Long expectedRepositoryVersion) {
}
