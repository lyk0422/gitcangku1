package com.example.starter.api.dto;

import jakarta.validation.constraints.PositiveOrZero;

/**
 * 撤回请求体（可选）：携带期望的仓库版本号。
 *
 * @param expectedRepositoryVersion 期望的仓库版本号；为 null 表示不校验，
 *                                  非 null 时与当前仓库版本不一致返回 409
 */
public record WithdrawRequest(@PositiveOrZero Long expectedRepositoryVersion) {
}
