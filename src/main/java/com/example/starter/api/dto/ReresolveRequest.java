package com.example.starter.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 锁文件重解析请求：已有锁文件标识与期望的仓库版本号。
 * 重解析键通过请求头 X-Reresolve-Key 传递。
 */
public record ReresolveRequest(
        @NotNull Long lockId,
        @NotNull @PositiveOrZero Long expectedRepositoryVersion) {
}
