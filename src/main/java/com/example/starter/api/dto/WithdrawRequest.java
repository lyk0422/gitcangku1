package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 制品版本撤回请求。
 *
 * @param operator                  执行本次撤回的非空操作者
 * @param expectedRepositoryVersion 客户端看到的仓库版本号，服务端在写事务内核对，不一致返回 409
 */
public record WithdrawRequest(
        @NotBlank String operator,
        @NotNull @PositiveOrZero Long expectedRepositoryVersion) {
}
